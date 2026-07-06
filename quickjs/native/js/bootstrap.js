// The engine bootstrap: the whole pipe protocol + streaming JSON + host-scheduled timers, built on
// the transport entry points (__jsOpen/__jsData/__jsClose/__jsCloseRead/__jsNotify in;
// __hostOpen/__hostData/__hostClose/__hostCloseRead/__hostNotify out) and the native pipe/JSON
// helpers (__segTake/__segRelease/__utf8Decode, __json*). Compiled to bytecode at build time and
// embedded — this file is never shipped as source.
//
//  - ymix.open(command) -> a pipe to Kotlin (this side is the client).
//  - ymix.onAccept(command, async (pipe) => ...) -> handle pipes Kotlin opens to us (server side).
//  - a pipe has send(buf,off,len), await receive(buf,off,len) (-> count or -1), close(reason?),
//    closeRead(), .command.
// Kotlin opens odd pipe ids, JS opens even ones, so the two id spaces never collide.
//
// MEMORY MODEL (see PIPE_STREAMING.md): inbound bytes live in C pool segments — __jsData hands us a
// one-shot zero-copy view of the host's bytes (DETACHED when the call returns); we copy straight
// into a parked reader's buffer (rendezvous, one copy) and stash only the remainder into pooled
// segments via __segTake (never a JS-heap copy of the payload). CRITICAL-REGION RULE: the
// synchronous _data path must never call a host-crossing native (__host*) — only pure-C ones
// (__segTake); promise resolutions merely schedule microtasks. DRAIN notifies are emitted from read
// paths only, which honors that by construction.

(function () {
  // Encode one or more strings to UTF-8, concatenated. Multi-arg (utf8Encode(str, "\n")) avoids a
  // JS string concat first — QuickJS strings are immutable, so `a + b` would allocate and copy.
  // (Small inputs only — pipes carry raw bytes; big text goes through the buffered sink's
  // stream-encode, and decoding is native __utf8Decode.)
  function utf8Encode() {
    var out = [];
    for (var a = 0; a < arguments.length; a++) {
      var str = arguments[a];
      for (var i = 0; i < str.length; i++) {
        var cp = str.codePointAt(i);
        if (cp > 0xFFFF) i++;
        if (cp < 0x80) out.push(cp);
        else if (cp < 0x800) out.push(0xC0 | (cp >> 6), 0x80 | (cp & 0x3F));
        else if (cp < 0x10000) out.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
        else out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
      }
    }
    return new Uint8Array(out);
  }

  // ---- Streaming JSON serialize: walk a value and write its tokens straight into a sink, so
  // neither the whole JSON string nor a Uint8Array is ever materialized (unlike __stringifyJson).
  // Matches JSON.stringify: honors toJSON(); NaN/Infinity -> null; a member whose value is
  // undefined/function/symbol is omitted in objects and becomes null in arrays. (Lone surrogates go
  // out as WTF-8, not \uXXXX — consistent with utf8Encode across the stack.)
  function jsonResolve(v) {
    return (v !== null && (typeof v === "object" || typeof v === "bigint") && typeof v.toJSON === "function") ? v.toJSON() : v;
  }
  // async: the sink's writeUtf8 may suspend for backpressure, so the walk awaits it. Tokens are small
  // and usually just fill the segment (synchronous), so awaits mostly resolve on the same tick.
  async function jsonWriteString(sink, s) {
    var runStart = 0, n = s.length, esc;
    await sink.writeUtf8("\"");
    for (var i = 0; i < n; i++) {
      var c = s.charCodeAt(i);
      esc = null;
      if (c === 0x22) esc = "\\\"";
      else if (c === 0x5C) esc = "\\\\";
      else if (c === 0x0A) esc = "\\n";
      else if (c === 0x0D) esc = "\\r";
      else if (c === 0x09) esc = "\\t";
      else if (c === 0x08) esc = "\\b";
      else if (c === 0x0C) esc = "\\f";
      else if (c < 0x20) esc = "\\u" + ("000" + c.toString(16)).slice(-4);
      if (esc !== null) {
        // write the safe run so far + the escape in one call (variadic, no string concat)
        if (i > runStart) await sink.writeUtf8(s.slice(runStart, i), esc);
        else await sink.writeUtf8(esc);
        runStart = i + 1;
      }
    }
    if (n > runStart) await sink.writeUtf8(s.slice(runStart, n), "\"");
    else await sink.writeUtf8("\"");
  }
  async function jsonWriteValue(sink, v) {
    var t = typeof v;
    if (v === null) await sink.writeUtf8("null");
    else if (t === "boolean") await sink.writeUtf8(v ? "true" : "false");
    else if (t === "number") await sink.writeUtf8(isFinite(v) ? ("" + v) : "null");
    else if (t === "string") await jsonWriteString(sink, v);
    else if (t === "bigint") throw new TypeError("Do not know how to serialize a BigInt");
    else if (Array.isArray(v)) {
      await sink.writeUtf8("[");
      for (var i = 0; i < v.length; i++) {
        if (i > 0) await sink.writeUtf8(",");
        var e = jsonResolve(v[i]), et = typeof e;
        if (e === undefined || et === "function" || et === "symbol") await sink.writeUtf8("null");
        else await jsonWriteValue(sink, e);
      }
      await sink.writeUtf8("]");
    } else {
      await sink.writeUtf8("{");
      var keys = Object.keys(v), first = true;
      for (var k = 0; k < keys.length; k++) {
        var key = keys[k], mv = jsonResolve(v[key]), vt = typeof mv;
        if (mv === undefined || vt === "function" || vt === "symbol") continue; // omit the key
        if (!first) await sink.writeUtf8(",");
        first = false;
        await jsonWriteString(sink, key);
        await sink.writeUtf8(":");
        await jsonWriteValue(sink, mv);
      }
      await sink.writeUtf8("}");
    }
  }

  var pipes = {};
  var accepts = {};
  var nextId = 2; // JS opens even ids; Kotlin opens odd.
  var PIPE_CAP = 65536, PIPE_LOW = 32768; // per-direction buffer bound (must match QuickJsRuntime.kt)
  var DRAIN = 1;                          // notify code (must match NOTIFY_DRAIN)
  var EOF = -1, WOULD_BLOCK = -2;         // take(): EOF is the public receive() value; WOULD_BLOCK is internal
  function brokenPipe() { return new Error("EPIPE: broken pipe"); }

  // Each pipe direction is a bounded, flow-controlled byte stream. Inbound bytes queue here as
  // Uint8Array views over pooled C segments (we're the reader — the queue is just views+counters,
  // the bytes stay in C memory); outbound writes are partial (__hostData returns the accepted
  // count) and park for a DRAIN notify when the host's buffer is full. All on the engine thread.
  function makePipe(id, command) {
    // inbound: queue of segment views drained through (head, headPos); qpos is a cursor, not
    // shift() (which memmoves the tail). One outstanding receive at a time (waiter) — either a
    // {buffer,offset,len} destination park (direct delivery) or a bare "readable" park (scanners).
    var queue = [], qpos = 0, head = null, headPos = 0, buffered = 0, waiter = null;
    var writeEnded = false, resetReason = null, readClosed = false, hostBlocked = false;
    // outbound: writer = the parked partial send {buffer, pos, end, resolve, reject}.
    var peerReadClosed = false, weEnded = false, writer = null;

    function releaseInbound() {
      if (head) { __segRelease(head); head = null; }
      for (var i = qpos; i < queue.length; i++) __segRelease(queue[i]);
      queue.length = 0; qpos = 0; headPos = 0; buffered = 0;
    }

    // Pop up to len bytes across segments: count, EOF, or WOULD_BLOCK (caller parks). Fully-drained
    // segments return to the C pool eagerly. Emits the DRAIN notify the host writer is owed — this
    // only ever runs from read paths, never inside _data (the critical-region rule).
    function take(buffer, offset, len) {
      var total = 0;
      while (total < len) {
        if (!head || headPos >= head.length) {
          if (head) { __segRelease(head); head = null; headPos = 0; }
          if (qpos < queue.length) {
            head = queue[qpos++];
            if (qpos === queue.length) { queue.length = 0; qpos = 0; }
          } else break;
        }
        var n = Math.min(len - total, head.length - headPos);
        if (headPos === 0 && n === head.length) buffer.set(head, offset + total);
        else buffer.set(head.subarray(headPos, headPos + n), offset + total);
        headPos += n; total += n; buffered -= n;
      }
      if (head && headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
      if (total > 0) {
        if (hostBlocked && buffered <= PIPE_LOW) { hostBlocked = false; __hostNotify(id, DRAIN); }
        return total;
      }
      return writeEnded ? EOF : WOULD_BLOCK;
    }

    // No more data either way — drop routing.
    function maybeReap() { if (weEnded && (writeEnded || readClosed)) delete pipes[id]; }

    return {
      id: id,
      command: command,

      // Deliver buffer[offset, offset+len) to the host. __hostData consumes synchronously (parked
      // Kotlin reader and/or its okio buffer) and returns the accepted count. Fully accepted ->
      // undefined (nothing to allocate; `await undefined` is a no-op). Partially accepted -> a
      // Promise that finishes the write as DRAIN notifies arrive — the buffer must stay unmodified
      // until it resolves.
      send: function (buffer, offset, len) {
        var o = offset || 0, l = (len === undefined ? buffer.length - o : len);
        if (l === 0) return;
        if (weEnded) return Promise.reject(new Error("send() after close()")); // our own half-close is a misuse
        if (peerReadClosed) return Promise.reject(brokenPipe());
        if (writer) throw new Error("concurrent send() on the same pipe");     // sequential: await one send first
        var written = __hostData(id, buffer, o, l);
        if (written < 0) { peerReadClosed = true; return Promise.reject(brokenPipe()); }
        if (written >= l) return;
        return new Promise(function (res, rej) {
          writer = { buffer: buffer, pos: o + written, end: o + l, resolve: res, reject: rej };
        });
      },

      // Receive up to len bytes; yields count, or -1 at EOF; throws the RESET reason. Fast path:
      // return synchronously when bytes are buffered (or ended) — no Promise, no microtask hop.
      receive: function (buffer, offset, len) {
        var o = offset || 0, l = (len === undefined ? buffer.length - o : len);
        if (readClosed) throw new Error("receive() after closeRead()");
        if (waiter) throw new Error("concurrent receive() on the same pipe");
        if (resetReason !== null) throw new Error(resetReason);
        if (l === 0) return 0;
        var n = take(buffer, o, l);
        if (n !== WOULD_BLOCK) return n;
        return new Promise(function (resolve, reject) {
          waiter = { buffer: buffer, offset: o, len: l, resolve: resolve, reject: reject };
        });
      },

      // Park until the inbound queue is actionable (data buffered / EOF / RESET) WITHOUT a
      // destination buffer — incoming bytes stay queued for in-place scanning (buffered source).
      // Returns undefined when already actionable, else a Promise.
      awaitReadable: function () {
        if (readClosed) throw new Error("receive() after closeRead()");
        if (waiter) throw new Error("concurrent receive() on the same pipe");
        if (resetReason !== null) throw new Error(resetReason);
        if (buffered > 0 || writeEnded) return;
        return new Promise(function (resolve, reject) {
          waiter = { buffer: null, resolve: resolve, reject: reject };
        });
      },

      // Write-side half-close: END (no reason) or RESET (string reason — the peer's receive throws).
      close: function (reason) {
        if (weEnded) return;
        weEnded = true;
        __hostClose(id, reason === undefined ? null : reason);
        maybeReap();
      },

      // Abandon the read side: release the segments and make the host's next send EPIPE.
      closeRead: function () {
        if (readClosed) return;
        readClosed = true;
        releaseInbound();
        __hostCloseRead(id);
        if (waiter) { var w = waiter; waiter = null; w.reject(new Error("receive() after closeRead()")); } // in-flight receive is a misuse
        maybeReap();
      },

      // ---- transport entry points (host -> us) ----

      // Host wrote bytes. `u8` is a one-shot zero-copy view of the HOST's memory — it is detached
      // the moment this returns, so consume it now: rendezvous straight into a parked reader's
      // buffer, then __segTake the remainder into pooled segments up to the cap. Returns the
      // accepted count (< len parks the host writer until DRAIN), or -1 after closeRead. This path
      // may run under a JNI critical region: pure-C natives only, no __host* calls.
      _data: function (u8) {
        if (readClosed) return -1;
        var len = u8.length, pos = 0;
        var w = waiter;
        if (w) {
          waiter = null;
          if (w.buffer) { // destination park: direct delivery (the queue is empty by construction)
            var n = Math.min(len, w.len);
            if (n === len) w.buffer.set(u8, w.offset);
            else w.buffer.set(u8.subarray(0, n), w.offset);
            pos = n;
            w.resolve(n);
          } else {
            w.resolve(); // readable park: wake the scanner; the bytes land in the queue below
          }
        }
        while (pos < len && buffered < PIPE_CAP) {
          var seg = __segTake(u8, pos, Math.min(len - pos, PIPE_CAP - buffered));
          queue.push(seg);
          buffered += seg.length;
          pos += seg.length;
        }
        if (pos < len) hostBlocked = true; // we owe the host a DRAIN once we read back below LOW
        return pos;
      },

      // Host closed its write end: reason null = END (EOF once drained), string = RESET (discard
      // pending data; parked/next receive throws the reason).
      _end: function (reason) {
        writeEnded = true;
        if (reason !== null && reason !== undefined) {
          resetReason = String(reason);
          releaseInbound();
          if (waiter) { var w = waiter; waiter = null; w.reject(new Error(resetReason)); }
        } else if (waiter) {
          var w2 = waiter; waiter = null;
          if (w2.buffer) w2.resolve(EOF); // a destination park implies an empty queue -> EOF now
          else w2.resolve();              // scanner wakes and sees writeEnded
        }
        maybeReap();
      },

      // Host abandoned its read end: our sends are EPIPE from now on.
      _peerCloseRead: function () {
        peerReadClosed = true;
        var w = writer;
        if (w) { writer = null; w.reject(brokenPipe()); }
      },

      // Flow control from the host: DRAIN — its buffer emptied to the low-water mark; continue the
      // parked partial send from where it stopped.
      _notify: function (code) {
        var w = writer;
        if (!w) return;
        var written = __hostData(id, w.buffer, w.pos, w.end - w.pos);
        if (written < 0) { writer = null; peerReadClosed = true; w.reject(brokenPipe()); return; }
        w.pos += written;
        if (w.pos >= w.end) { writer = null; w.resolve(); }
        // else stay parked for the next DRAIN
      }
    };
  }

  // Kotlin opened a pipe to us.
  globalThis.__jsOpen = function (id, command) {
    var pipe = makePipe(id, command);
    pipes[id] = pipe;
    var handler = accepts[command];
    // closeRead()+close() refuse both directions; their maybeReap() drops pipes[id] — no explicit delete.
    if (handler) Promise.resolve().then(function () { return handler(pipe); })
      .catch(function (e) { pipe.closeRead(); pipe.close(String((e && e.message) || e)); }); // RESET: surface the failure
    else { pipe.closeRead(); pipe.close(); }
  };
  // Kotlin wrote bytes (one-shot zero-copy view; see _data). Returns the accepted count / -1 EPIPE.
  globalThis.__jsData = function (id, u8) {
    var p = pipes[id]; return p ? p._data(u8) : -1;
  };
  // Kotlin closed its write end — END (reason null) or RESET (reason string).
  globalThis.__jsClose = function (id, reason) {
    var p = pipes[id]; if (p) p._end(reason);
  };
  // Kotlin abandoned its read end — our sends get EPIPE.
  globalThis.__jsCloseRead = function (id) {
    var p = pipes[id]; if (p) p._peerCloseRead();
  };
  // Kotlin flow-control signal: DRAIN (continue the parked partial send).
  globalThis.__jsNotify = function (id, code) {
    var p = pipes[id]; if (p) p._notify(code);
  };

  globalThis.ymix = {
    open: function (command) {
      var id = nextId; nextId += 2;
      var pipe = makePipe(id, command);
      pipes[id] = pipe;
      __hostOpen(id, command);
      return pipe;
    },
    onAccept: function (command, handler) { accepts[command] = handler; },
    utf8Encode: utf8Encode,
    // Native decode (JS_NewStringLen) — astral-correct, no per-codepoint JS loop. Range args avoid
    // a subarray view: utf8Decode(bytes, from, to).
    utf8Decode: function (bytes, from, to) {
      var f = from === undefined ? 0 : from;
      return __utf8Decode(bytes, f, (to === undefined ? bytes.length : to) - f);
    },
    // Read a whole pipe stream (until EOF) into one Uint8Array.
    readAll: async function (pipe) {
      var chunks = [], total = 0, buf = new Uint8Array(4096);
      for (;;) {
        var n = await pipe.receive(buf, 0, buf.length);
        if (n < 0) break;
        if (n > 0) { chunks.push(buf.slice(0, n)); total += n; }
      }
      var out = new Uint8Array(total), p = 0;
      for (var i = 0; i < chunks.length; i++) { out.set(chunks[i], p); p += chunks[i].length; }
      return out;
    },

    // A buffered source+sink over a pipe (okio BufferedSource/BufferedSink style).
    buffered: function (pipe) {
      // source window: valid bytes are rbuf[rbeg, rend)
      var rbuf = new Uint8Array(4096), rbeg = 0, rend = 0, reof = false;
      // sink segment: a FIXED-capacity buffer (never grows) that coalesces small writes. It
      // auto-flushes when full and is bypassed for large writes, so sink memory stays ~one segment
      // no matter how many bytes are written. Flush boundaries are invisible to the peer — the pipe
      // is a byte stream and Kotlin's buffered source reassembles across them.
      var wbuf = new Uint8Array(8192), wlen = 0;

      async function fill() {
        if (reof) return 0;
        if (rbeg > 0) { rbuf.copyWithin(0, rbeg, rend); rend -= rbeg; rbeg = 0; }
        if (rend >= rbuf.length) { var nb = new Uint8Array(rbuf.length * 2); nb.set(rbuf); rbuf = nb; } // full: rend === length
        var n = await pipe.receive(rbuf, rend, rbuf.length - rend);
        if (n < 0) { reof = true; return 0; }
        rend += n; return n;
      }

      return {
        // ---- BufferedSource ----
        // Read up to len bytes into buffer at offset; returns count, or -1 at EOF.
        async read(buffer, offset, len) {
          var o = offset || 0;
          var l = (len === undefined ? buffer.length - o : len);
          if (l === 0) return 0;
          if (rend - rbeg === 0 && (await fill()) === 0) return -1;
          var n = Math.min(l, rend - rbeg);
          buffer.set(rbuf.subarray(rbeg, rbeg + n), o);
          rbeg += n;
          return n;
        },
        // One line up to '\n' (trailing '\r' stripped). Non-strict (default): a final line without
        // a '\n' is returned, and a clean EOF returns null. strict=true (okio readUtf8LineStrict):
        // requires a '\n' — throws if EOF is reached first.
        async readUtf8Line(strict) {
          var scanned = 0; // bytes past rbeg already known to hold no '\n'; relative to rbeg, so it survives fill()'s compaction
          for (;;) {
            for (var i = rbeg + scanned; i < rend; i++) {
              if (rbuf[i] === 10) {
                var e = (i > rbeg && rbuf[i - 1] === 13) ? i - 1 : i;
                var line = ymix.utf8Decode(rbuf, rbeg, e);
                rbeg = i + 1;
                return line;
              }
            }
            scanned = rend - rbeg;
            if ((await fill()) === 0) {
              if (strict) throw new Error("readUtf8Line(strict): stream exhausted before a newline");
              if (rend > rbeg) { var l = ymix.utf8Decode(rbuf, rbeg, rend); rbeg = rend; return l; }
              return null;
            }
          }
        },
        // Parse ONE top-level JSON object / array from the stream via the native streaming parser.
        // Bytes are fed from the window as they arrive — the whole body is never buffered. The root
        // type is checked on its FIRST byte (fail fast if the body is a scalar, the wrong container,
        // or e.g. an HTML error page). Stops at the value's end: trailing bytes stay in the buffer,
        // so you can read the JSON then keep reading (e.g. "{json}tail" on one pipe).
        async readJsonObject() { return this._readJson(1); },
        async readJsonArray() { return this._readJson(2); },
        async _readJson(expect) {
          var p = __jsonNew(expect);
          for (;;) {
            if (rend > rbeg) {
              rbeg += __jsonPush(p, rbuf, rbeg, rend - rbeg);
              if (__jsonDone(p)) return __jsonEnd(p);   // value complete; tail (if any) left in [rbeg,rend)
            }
            if ((await fill()) === 0) return __jsonEnd(p); // EOF: finalize (or throw if incomplete)
          }
        },
        // All remaining bytes, decoded as UTF-8 (one native decode over the whole array).
        async readUtf8() {
          var all = await this.readByteArray();
          return ymix.utf8Decode(all, 0, all.length);
        },
        // All remaining bytes to EOF (raw) — e.g. to hand to __parseJson.
        async readByteArray() {
          var chunks = [], total = 0;
          for (;;) {
            if (rend > rbeg) { var c = rbuf.slice(rbeg, rend); chunks.push(c); total += c.length; rbeg = rend; }
            if ((await fill()) === 0) break;
          }
          var all = new Uint8Array(total), p = 0;
          for (var k = 0; k < chunks.length; k++) { all.set(chunks[k], p); p += chunks[k].length; }
          return all;
        },

        // ---- BufferedSink: a fixed segment; small writes coalesce, large writes bypass it ----
        // Every write is async and applies backpressure: it stays synchronous while it only fills the
        // 8 KB segment (or flushes with room), and suspends only when a flush hits a full pipe. Writes
        // must be sequential — `await` one before the next (a second overlapping send throws). Internal
        // flushes use `if (p) await p`, so the fast path costs no microtask.
        // Append b. A large input (>= a segment) bypasses the buffer: each segment-sized slice is
        // awaited, so a few-MB array is paced by the reader and never held whole.
        async writeBytes(b) {
          var n = b.length, p;
          if (n >= wbuf.length) {
            p = this.flush(); if (p) await p;
            for (var off = 0; off < n; off += wbuf.length) {
              p = pipe.send(b, off, Math.min(wbuf.length, n - off));
              if (p) await p;
            }
            return;
          }
          if (wlen + n > wbuf.length) { p = this.flush(); if (p) await p; }
          wbuf.set(b, wlen); wlen += n;
        },
        // Stream-encode UTF-8 straight into the segment, flushing when it fills — never materializes
        // the whole encoded array. Variadic: writeUtf8(a, b, c) encodes the parts back-to-back with no
        // `a + b + c` first (QuickJS strings are immutable, so + would allocate and copy).
        async writeUtf8() {
          for (var a = 0; a < arguments.length; a++) {
            var str = arguments[a], p;
            for (var i = 0; i < str.length; i++) {
              var cp = str.codePointAt(i);
              if (cp > 0xFFFF) i++;
              // Flush only when this codepoint's own bytes don't fit — reserving the worst-case 4
              // would flush up to 3 bytes early and underpack the segment.
              if (cp < 0x80) {
                if (wlen + 1 > wbuf.length) { p = this.flush(); if (p) await p; }
                wbuf[wlen++] = cp;
              } else if (cp < 0x800) {
                if (wlen + 2 > wbuf.length) { p = this.flush(); if (p) await p; }
                wbuf[wlen++] = 0xC0 | (cp >> 6); wbuf[wlen++] = 0x80 | (cp & 0x3F);
              } else if (cp < 0x10000) {
                if (wlen + 3 > wbuf.length) { p = this.flush(); if (p) await p; }
                wbuf[wlen++] = 0xE0 | (cp >> 12); wbuf[wlen++] = 0x80 | ((cp >> 6) & 0x3F); wbuf[wlen++] = 0x80 | (cp & 0x3F);
              } else {
                if (wlen + 4 > wbuf.length) { p = this.flush(); if (p) await p; }
                wbuf[wlen++] = 0xF0 | (cp >> 18); wbuf[wlen++] = 0x80 | ((cp >> 12) & 0x3F); wbuf[wlen++] = 0x80 | ((cp >> 6) & 0x3F); wbuf[wlen++] = 0x80 | (cp & 0x3F);
              }
            }
          }
        },
        // writeUtf8(...args) then a single '\n' — no concat, no separate encode pass.
        async writeUtf8Line() {
          await this.writeUtf8.apply(this, arguments);
          if (wlen + 1 > wbuf.length) { var p = this.flush(); if (p) await p; }
          wbuf[wlen++] = 10;
        },
        // Serialize value to JSON by walking it (jsonWriteValue) — streams tokens into the segment,
        // never building the whole JSON string or a Uint8Array. For big outbound data prefer JSONL
        // (one writeObjectAsJson per line) so peak memory stays ~one item.
        async writeObjectAsJson(value) {
          var v = jsonResolve(value), t = typeof v;
          if (v === undefined || t === "function" || t === "symbol") await this.writeUtf8("null");
          else await jsonWriteValue(this, v);
        },
        // Push the staged segment to the pipe. Returns undefined (nothing staged, or fully accepted)
        // or a Promise that resolves once the pipe accepts the rest (backpressure). The segment must
        // not be refilled until that promise resolves — every caller here awaits it first.
        flush() { if (wlen === 0) return; var p = pipe.send(wbuf, 0, wlen); wlen = 0; return p; },
        async close(reason) { var p = this.flush(); if (p) await p; pipe.close(reason); }
      };
    }
  };
})();

// ---- Host-scheduled timers: setTimeout/setInterval over the pipe protocol (see Timers.kt). A
// cleared timer's async loop ends when the host closes the pipe (receive -> EOF). A callback that
// throws rejects this async task and, for setInterval, stops the interval.
(function () {
  function startTimer(cb, ms, command, repeat, args) {
    var pipe = ymix.open(command);
    pipe.send(ymix.utf8Encode(String(Math.max(0, Math.floor(Number(ms) || 0)))));
    var buf = new Uint8Array(1);
    (async function () {
      try {
        for (;;) {
          var n = await pipe.receive(buf, 0, 1);
          if (n < 1) break;                 // cleared: host closed the pipe
          cb.apply(null, args);
          if (!repeat) break;
        }
      } finally {
        pipe.close();
      }
    })();
    return pipe;                            // handle; clear*() closes it
  }
  globalThis.setTimeout = function (cb, ms) {
    return startTimer(cb, ms, "setTimeout", false, Array.prototype.slice.call(arguments, 2));
  };
  globalThis.setInterval = function (cb, ms) {
    return startTimer(cb, ms, "setInterval", true, Array.prototype.slice.call(arguments, 2));
  };
  globalThis.clearTimeout = function (handle) { if (handle && handle.close) handle.close(); };
  globalThis.clearInterval = globalThis.clearTimeout;
})();
