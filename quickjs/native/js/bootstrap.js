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
// segments via __segTake (never a JS-heap copy of the payload). The buffered source scans those
// queued segments IN PLACE — lines are decoded and JSON is parsed straight out of C memory.
// CRITICAL-REGION RULE: the synchronous _data path must never call a host-crossing native
// (__host*) — only pure-C ones (__segTake); promise resolutions merely schedule microtasks. DRAIN
// notifies are emitted from read paths only, which honors that by construction.

(function () {
  // Encode one or more strings to UTF-8, concatenated. Multi-arg (utf8Encode(str, "\n")) avoids a
  // JS string concat first — QuickJS strings are immutable, so `a + b` would allocate and copy.
  // (Small inputs only — big text goes through the buffered sink's stream-encode, and decoding is
  // native __utf8Decode.)
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

  // toJSON hook (JSON.stringify semantics) — used by the sink's writeObjectAsJson token pump.
  function jsonResolve(v) {
    return (v !== null && (typeof v === "object" || typeof v === "bigint") && typeof v.toJSON === "function") ? v.toJSON() : v;
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

    // Advance head to the next unread segment; false when nothing is buffered.
    function ensureHead() {
      if (head && headPos < head.length) return true;
      if (head) { __segRelease(head); head = null; headPos = 0; }
      if (qpos >= queue.length) return false;
      head = queue[qpos++]; headPos = 0;
      if (qpos === queue.length) { queue.length = 0; qpos = 0; }
      return true;
    }

    // Emit the DRAIN notify the host writer is owed. Only ever called from read paths, never
    // inside _data (the critical-region rule).
    function drainCheck() {
      if (hostBlocked && buffered <= PIPE_LOW) { hostBlocked = false; __hostNotify(id, DRAIN); }
    }

    // Pop up to len bytes across segments: count, EOF, or WOULD_BLOCK (caller parks). Fully-drained
    // segments return to the C pool eagerly.
    function take(buffer, offset, len) {
      var total = 0;
      while (total < len && ensureHead()) {
        var n = Math.min(len - total, head.length - headPos);
        if (headPos === 0 && n === head.length) buffer.set(head, offset + total);
        else buffer.set(head.subarray(headPos, headPos + n), offset + total);
        headPos += n; total += n; buffered -= n;
      }
      if (head && headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
      if (total > 0) { drainCheck(); return total; }
      return writeEnded ? EOF : WOULD_BLOCK;
    }

    // Drop n buffered bytes (consumed by an in-place scanner).
    function discard(n) {
      var left = n;
      while (left > 0 && ensureHead()) {
        var m = Math.min(left, head.length - headPos);
        headPos += m; left -= m; buffered -= m;
      }
      if (head && headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
      drainCheck();
    }

    // Byte at logical offset k (0 = first unread byte), or -1 past the end. No consumption.
    function byteAt(k) {
      if (head) {
        var avail = head.length - headPos;
        if (k < avail) return head[headPos + k];
        k -= avail;
      }
      for (var q = qpos; q < queue.length; q++) {
        if (k < queue[q].length) return queue[q][k];
        k -= queue[q].length;
      }
      return -1;
    }

    // Logical index of the next '\n' at/after `from`, or -1. No consumption.
    function scanNewline(from) {
      var base = 0;
      if (head) {
        var avail = head.length - headPos;
        if (from < avail) {
          for (var i = headPos + from; i < head.length; i++) if (head[i] === 10) return i - headPos;
          from = 0;
        } else from -= avail;
        base += avail;
      }
      for (var q = qpos; q < queue.length; q++) {
        var seg = queue[q], slen = seg.length;
        if (from < slen) {
          for (var j = from; j < slen; j++) if (seg[j] === 10) return base + j;
          from = 0;
        } else from -= slen;
        base += slen;
      }
      return -1;
    }

    // Decode the first `e` buffered bytes as UTF-8 without consuming them. Fast path: the range
    // lives in one segment -> native decode straight out of C memory; a range spanning segments is
    // assembled once into a scratch array (rare — lines are small).
    function decodeRange(e) {
      if (e === 0) return "";
      if (head && e <= head.length - headPos) return __utf8Decode(head, headPos, e);
      if (!head && qpos < queue.length && e <= queue[qpos].length) return __utf8Decode(queue[qpos], 0, e);
      var scratch = new Uint8Array(e), copied = 0;
      if (head) {
        var n = Math.min(e, head.length - headPos);
        scratch.set(head.subarray(headPos, headPos + n), 0);
        copied = n;
      }
      for (var q = qpos; copied < e && q < queue.length; q++) {
        var seg = queue[q], m = Math.min(e - copied, seg.length);
        scratch.set(m === seg.length ? seg : seg.subarray(0, m), copied);
        copied += m;
      }
      return __utf8Decode(scratch, 0, e);
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

      // Park until MORE input (or EOF/RESET) arrives, WITHOUT a destination buffer — incoming
      // bytes stay queued for in-place scanning. Scanners always scan first, then park: nothing
      // can arrive between the scan and the park (single thread), so no wakeup is lost. Returns
      // undefined when already ended (rescan handles the final bytes), else a Promise.
      awaitReadable: function () {
        if (readClosed) throw new Error("receive() after closeRead()");
        if (waiter) throw new Error("concurrent receive() on the same pipe");
        if (resetReason !== null) throw new Error(resetReason);
        if (writeEnded) return;
        return new Promise(function (resolve, reject) {
          waiter = { buffer: null, resolve: resolve, reject: reject };
        });
      },

      // ---- in-place scanners (the buffered source drives these; no copy out of C memory) ----

      // One line: returns the decoded string (consumed through its '\n', '\r' stripped), null at a
      // clean empty EOF, or a NUMBER = "no newline in the `buffered` bytes scanned so far" — the
      // caller awaits more input and rescans from there. strict: throw at EOF instead of returning
      // the final unterminated line.
      _lineSync: function (scanFrom, strict) {
        if (resetReason !== null) throw new Error(resetReason);
        var nl = scanNewline(scanFrom);
        if (nl >= 0) {
          var e = (nl > 0 && byteAt(nl - 1) === 13) ? nl - 1 : nl;
          var line = decodeRange(e);
          discard(nl + 1);
          return line;
        }
        if (!writeEnded) return buffered;
        if (strict) throw new Error("readUtf8Line(strict): stream exhausted before a newline");
        if (buffered === 0) return null;
        var last = decodeRange(buffered);
        discard(buffered);
        return last;
      },

      // Feed all buffered bytes to a streaming JSON parser, segment by segment, consuming exactly
      // what it takes (a completed value leaves its tail queued). Returns 1 when the value is
      // complete, 0 when more input is needed, -1 at EOF (caller finalizes -> value or throw).
      _jsonFeed: function (p) {
        if (resetReason !== null) throw new Error(resetReason);
        while (ensureHead()) {
          var consumed = __jsonPush(p, head, headPos, head.length - headPos);
          headPos += consumed; buffered -= consumed;
          if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
          if (__jsonDone(p)) { drainCheck(); return 1; }
        }
        drainCheck();
        return writeEnded ? -1 : 0;
      },

      // Transfer ALL buffered segments to the caller as views trimmed to their unread range —
      // no copy. The caller assembles/consumes them and MUST __segRelease each when done.
      _takeChunks: function () {
        if (resetReason !== null) throw new Error(resetReason);
        var out = [];
        if (head) {
          if (headPos < head.length) out.push(headPos === 0 ? head : head.subarray(headPos));
          else __segRelease(head);
          head = null; headPos = 0;
        }
        for (var q = qpos; q < queue.length; q++) out.push(queue[q]);
        queue.length = 0; qpos = 0; buffered = 0;
        drainCheck();
        return out;
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
    // Read a whole pipe stream (until EOF) into one Uint8Array (single copy: segments -> result).
    readAll: function (pipe) {
      return ymix.buffered(pipe).readByteArray();
    },

    // A buffered source+sink over a pipe (okio BufferedSource/BufferedSink style). The source has
    // NO window of its own: the pipe's inbound queue of segment views IS the buffer, and the
    // scanners (readUtf8Line / readJson*) work on it in place — which also makes head + JSON body +
    // tail share one stream position for free.
    buffered: function (pipe) {
      // sink segment: a FIXED-capacity buffer (never grows) that coalesces small writes. It
      // auto-flushes when full and is bypassed for large writes, so sink memory stays ~one segment
      // no matter how many bytes are written. Flush boundaries are invisible to the peer — the pipe
      // is a byte stream and the reader reassembles across them.
      var wbuf = new Uint8Array(8192), wlen = 0;

      // ---- sink internals: PLAIN functions, never async. undefined = completed synchronously
      // (the common case), a Promise = real backpressure (a flush hit a full pipe), resumed via
      // .then(continue). QuickJS charges a full microtask round-trip (~0.4µs) for `await` even on
      // non-thenables and a promise allocation per async call, so the hot path must never touch
      // the microtask queue — suspension machinery is only paid when a write actually blocks.

      // Push the staged segment to the pipe. The segment must not be refilled until the returned
      // promise (if any) resolves — every continuation here honors that.
      function flushSink() {
        if (wlen === 0) return;
        var p = pipe.send(wbuf, 0, wlen);
        wlen = 0;
        return p;
      }

      // Stage one ASCII byte (structural JSON chars, '\n').
      function putByte(b) {
        if (wlen + 1 > wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wbuf[wlen++] = b; });
        }
        wbuf[wlen++] = b;
      }

      // Append b to the sink: coalesce into the segment; a large input (>= a segment) bypasses the
      // buffer and streams to the pipe in segment-sized slices, paced by the reader.
      function writeBytesFn(b) {
        var n = b.length;
        if (n >= wbuf.length) {
          var off = 0;
          function pump() {
            while (off < n) {
              var m = Math.min(wbuf.length, n - off);
              var q = pipe.send(b, off, m);
              off += m;
              if (q) return q.then(pump);
            }
          }
          var pf = flushSink();
          return pf ? pf.then(pump) : pump();
        }
        if (wlen + n > wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wbuf.set(b, wlen); wlen += n; });
        }
        wbuf.set(b, wlen); wlen += n;
      }

      // Encode str[from, to) via ONE native call (__utf8Into) straight into the segment — no
      // per-codepoint interpreted loop. Doesn't fit: flush and retry; bigger than a whole empty
      // segment (an oversized string token): encode it natively ONCE (__utf8Bytes) and stream the
      // bytes through the writeBytes bypass — memory is one encoded copy of that single token.
      function writeUtf8Native(str, from, to) {
        var r = __utf8Into(str, from, to, wbuf, wlen);
        if (r >= 0) { wlen += r; return; }
        if (-r <= wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wlen += __utf8Into(str, from, to, wbuf, wlen); });
          wlen += __utf8Into(str, from, to, wbuf, wlen);
          return;
        }
        return writeBytesFn(__utf8Bytes(str, from, to));
      }

      // Emit a JSON string literal (quotes + escapes) for s via ONE native call (__jsonStrInto) —
      // replaces both the interpreted escape scan and the encode loop. Same fits/flush/fallback
      // ladder as writeUtf8Native; only a segment-plus-sized string takes the streaming path.
      function writeJsonString(s) {
        var r = __jsonStrInto(s, wbuf, wlen);
        if (r >= 0) { wlen += r; return; }
        if (-r <= wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wlen += __jsonStrInto(s, wbuf, wlen); });
          wlen += __jsonStrInto(s, wbuf, wlen);
          return;
        }
        return writeJsonStringStream(s);
      }

      // Streaming fallback for a JSON string literal bigger than a whole segment (quotes, safe
      // runs, escapes). Runs are written straight from the source string (range encode, no slice).
      // State lives outside step(), so a rare suspension resumes exactly where it stopped.
      function writeJsonStringStream(s) {
        var n = s.length, i = 0, phase = 0, pendingEsc = null; // phase: 0=open quote, 1=scan, 2=close quote, 3=done
        function step() {
          for (;;) {
            var p;
            if (phase === 0) { phase = 1; p = putByte(34); if (p) return p.then(step); continue; }
            if (phase === 2) { phase = 3; p = putByte(34); if (p) return p.then(step); continue; }
            if (phase === 3) return;
            if (pendingEsc !== null) {
              var e = pendingEsc;
              pendingEsc = null;
              p = writeUtf8Native(e, 0, e.length);
              if (p) return p.then(step);
              continue;
            }
            // scan the next run of safe chars [i, j)
            var esc = null, j = i;
            for (; j < n; j++) {
              var c = s.charCodeAt(j);
              if (c === 0x22) esc = "\\\"";
              else if (c === 0x5C) esc = "\\\\";
              else if (c === 0x0A) esc = "\\n";
              else if (c === 0x0D) esc = "\\r";
              else if (c === 0x09) esc = "\\t";
              else if (c === 0x08) esc = "\\b";
              else if (c === 0x0C) esc = "\\f";
              else if (c < 0x20) esc = "\\u" + ("000" + c.toString(16)).slice(-4);
              else continue;
              break;
            }
            var from = i;
            if (j >= n) { phase = 2; i = j; }         // no more escapes: run, then close quote
            else { pendingEsc = esc; i = j + 1; }     // run, then this escape
            if (j > from) { p = writeUtf8Native(s, from, j); if (p) return p.then(step); }
          }
        }
        return step();
      }

      // Serialize a value as JSON via an explicit-stack token pump — no recursion (no depth limit)
      // and no per-token suspension points: tokens stream into the segment synchronously and the
      // pump chains .then(step) only when a flush actually blocks. Matches JSON.stringify: honors
      // toJSON(); NaN/Infinity -> null; undefined/function/symbol members are omitted in objects
      // and become null in arrays. (Lone surrogates go out as WTF-8, not \uXXXX — consistent with
      // utf8Encode across the stack.)
      function writeJsonValue(root) {
        var stack = [];   // container frames: {v, keys (null = array), i, phase, emitted, pk, pv}
        var pending = root, hasPending = true; // next VALUE to emit (already jsonResolve'd)

        function emitValue(v) { // scalar -> write it; container -> write its opener + push a frame
          var t = typeof v;
          if (v === null) return writeUtf8Native("null", 0, 4);
          if (t === "boolean") return v ? writeUtf8Native("true", 0, 4) : writeUtf8Native("false", 0, 5);
          if (t === "number") { var s = isFinite(v) ? ("" + v) : "null"; return writeUtf8Native(s, 0, s.length); }
          if (t === "string") return writeJsonString(v);
          if (t === "bigint") throw new TypeError("Do not know how to serialize a BigInt");
          if (Array.isArray(v)) { stack.push({ v: v, keys: null, i: 0, emitted: false }); return putByte(91); } // '['
          stack.push({ v: v, keys: Object.keys(v), i: 0, phase: 0, emitted: false, pk: null, pv: null });
          return putByte(123); // '{'
        }

        function step() {
          for (;;) {
            var p;
            if (hasPending) {
              var val = pending;
              pending = null; hasPending = false;
              p = emitValue(val);
              if (p) return p.then(step);
              continue;
            }
            if (stack.length === 0) return; // done
            var f = stack[stack.length - 1];
            if (f.keys === null) {
              // array frame: closer, or comma? + next element
              if (f.i >= f.v.length) { stack.pop(); p = putByte(93); if (p) return p.then(step); continue; } // ']'
              var e = jsonResolve(f.v[f.i]); f.i++;
              var et = typeof e;
              if (e === undefined || et === "function" || et === "symbol") e = null;
              pending = e; hasPending = true; // staged BEFORE the comma so a suspension resumes in order
              if (f.emitted) { p = putByte(44); if (p) return p.then(step); continue; } // ','
              f.emitted = true;
              continue;
            }
            // object frame phases: 0 = find member (+comma), 3 = key, 1 = colon, 2 = value
            if (f.phase === 3) { f.phase = 1; p = writeJsonString(f.pk); if (p) return p.then(step); continue; }
            if (f.phase === 1) { f.phase = 2; p = putByte(58); if (p) return p.then(step); continue; } // ':'
            if (f.phase === 2) { f.phase = 0; pending = f.pv; f.pv = null; hasPending = true; continue; }
            var found = false;
            while (f.i < f.keys.length) {
              var key = f.keys[f.i]; f.i++;
              var mv = jsonResolve(f.v[key]);
              var mt = typeof mv;
              if (mv === undefined || mt === "function" || mt === "symbol") continue; // omit the key
              f.pk = key; f.pv = mv; found = true;
              break;
            }
            if (!found) { stack.pop(); p = putByte(125); if (p) return p.then(step); continue; } // '}'
            f.phase = 3;
            if (f.emitted) { p = putByte(44); if (p) return p.then(step); continue; } // ','
            f.emitted = true;
          }
        }
        return step();
      }

      return {
        // ---- BufferedSource (in-place over the pipe's segment queue) ----
        // Read up to len bytes into buffer at offset; count, or -1 at EOF (pipe.receive semantics —
        // synchronous when bytes are buffered, direct-delivery rendezvous when parked).
        read(buffer, offset, len) {
          return pipe.receive(buffer, offset, len);
        },
        // One line up to '\n' (trailing '\r' stripped), decoded natively straight from the queued
        // segments. Non-strict (default): a final line without a '\n' is returned, and a clean EOF
        // returns null. strict=true (okio readUtf8LineStrict): requires a '\n' — throws at EOF.
        async readUtf8Line(strict) {
          var scanned = 0; // bytes already known to hold no '\n' (nothing is consumed while scanning)
          for (;;) {
            var r = pipe._lineSync(scanned, strict);
            if (typeof r !== "number") return r; // the line (string) or null at clean EOF
            scanned = r;
            var w = pipe.awaitReadable(); if (w) await w;
          }
        },
        // Parse ONE top-level JSON object / array via the native streaming parser, fed segment by
        // segment straight from C memory — the body bytes never enter the JS heap and the whole
        // body is never buffered. The root type is checked on its FIRST byte (fail fast if the body
        // is a scalar, the wrong container, or e.g. an HTML error page). Stops at the value's end:
        // trailing bytes stay queued, so you can read the JSON then keep reading ("{json}tail").
        async readJsonObject() { return this._readJson(1); },
        async readJsonArray() { return this._readJson(2); },
        async _readJson(expect) {
          var p = __jsonNew(expect);
          for (;;) {
            var s = pipe._jsonFeed(p);
            if (s !== 0) return __jsonEnd(p); // 1 = complete; -1 = EOF (finalize, or throw if incomplete)
            var w = pipe.awaitReadable(); if (w) await w;
          }
        },
        // All remaining bytes, decoded as UTF-8 (one native decode over the assembled array).
        async readUtf8() {
          var all = await this.readByteArray();
          return __utf8Decode(all, 0, all.length);
        },
        // All remaining bytes to EOF (raw) — segments transfer as views and are assembled with a
        // single copy into the result, then returned to the pool.
        async readByteArray() {
          var chunks = [], total = 0;
          for (;;) {
            var got = pipe._takeChunks();
            for (var i = 0; i < got.length; i++) { chunks.push(got[i]); total += got[i].length; }
            var w = pipe.awaitReadable();
            if (!w) { // ended: nothing more can arrive (anything present was taken above)
              var last = pipe._takeChunks();
              for (var j = 0; j < last.length; j++) { chunks.push(last[j]); total += last[j].length; }
              break;
            }
            await w;
          }
          var all = new Uint8Array(total), pos = 0;
          for (var k = 0; k < chunks.length; k++) {
            all.set(chunks[k], pos);
            pos += chunks[k].length;
            __segRelease(chunks[k]);
          }
          return all;
        },

        // ---- BufferedSink: a fixed segment; small writes coalesce, large writes bypass it ----
        // Sink writes are PLAIN functions: they return undefined when completed synchronously (the
        // common case — the write only filled the segment) or a Promise under real backpressure (a
        // flush hit a full pipe; buffers involved must stay unmodified until it resolves). `await`
        // works on either, but hot loops can skip the microtask hop QuickJS charges for awaiting a
        // non-thenable: `var p = io.writeUtf8(s); if (p) await p;`. Writes must be sequential —
        // finish one (await it, or see undefined) before the next.
        // Append b. A large input (>= a segment) bypasses the buffer: it streams to the pipe in
        // segment-sized slices, paced by the reader — a few-MB array is never held whole.
        writeBytes(b) { return writeBytesFn(b); },
        // Variadic: writeUtf8(a, b, c) stream-encodes the parts back-to-back with no `a + b + c`
        // first (QuickJS strings are immutable, so + would allocate and copy).
        writeUtf8() {
          if (arguments.length === 1) { var s = arguments[0]; return writeUtf8Native(s, 0, s.length); }
          var args = arguments, k = 0;
          function next() {
            while (k < args.length) {
              var str = args[k++];
              var p = writeUtf8Native(str, 0, str.length);
              if (p) return p.then(next);
            }
          }
          return next();
        },
        // Range form: encode str[from, to) with no .slice() (a slice copies the whole substring).
        writeUtf8Range(str, from, to) {
          return writeUtf8Native(str, from, to);
        },
        // writeUtf8(...args) then a single '\n' — no concat, no separate encode pass.
        writeUtf8Line() {
          var p = this.writeUtf8.apply(this, arguments);
          return p ? p.then(function () { return putByte(10); }) : putByte(10);
        },
        // Serialize value to JSON via the token pump (writeJsonValue) — streams tokens into the
        // segment, never building the whole JSON string or a Uint8Array, with no recursion and no
        // per-token suspension points. For big outbound data prefer JSONL (one writeObjectAsJson
        // per line) so peak memory stays ~one item.
        writeObjectAsJson(value) {
          var v = jsonResolve(value), t = typeof v;
          if (v === undefined || t === "function" || t === "symbol") return encodeRange("null", 0, 4);
          return writeJsonValue(v);
        },
        flush() { return flushSink(); },
        close(reason) {
          var p = flushSink();
          if (p) return p.then(function () { pipe.close(reason); });
          pipe.close(reason);
        }
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
