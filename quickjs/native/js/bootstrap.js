// The engine bootstrap: the whole pipe protocol + streaming JSON + host-scheduled timers, built on
// the transport entry points (__jsOpen/__jsData/__jsClose/__jsCloseRead/__jsDrain in;
// __hostOpen/__hostData/__hostClose/__hostCloseRead/__hostDrain out) and the native pipe/JSON
// helpers (__segTake/__segRelease/__utf8Decode, __json*). Compiled to bytecode at build time and
// embedded — this file is never shipped as source.
//
//  - ymix.open(command) -> a pipe to Kotlin (this side is the client).
//  - ymix.onAccept(command, async (pipe) => ...) -> handle pipes Kotlin opens to us (server side).
//  - a pipe is the whole okio-Buffer-style object (source + sink in one — there is no separate
//    "buffered" wrapper): the raw channel is send(buf,off,len) / await receive(buf,off,len) (-> count
//    or -1); buffered reads work its own inbound queue IN PLACE (readInt/readLong, readUtf8,
//    readUtf8Line, readJsonObject/readJsonArray, available()); buffered writes coalesce through a
//    lazily-allocated staging segment (writeInt/writeLong, writeBytes, writeUtf8[Range|Line],
//    writeObjectAsJson, flush()); then close(reason?) (flushes first) / closeRead() / .command.
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

  var pipes = {};
  var accepts = {};
  var nextId = 2; // JS opens even ids; Kotlin opens odd.
  var PIPE_CAP = 65536, PIPE_LOW = 32768; // per-direction buffer bound (must match QuickJsRuntime.kt)
  var EOF = -1, WOULD_BLOCK = -2;         // take(): EOF is the public receive() value; WOULD_BLOCK is internal
  function brokenPipe() { return new Error("EPIPE: broken pipe"); }

  // Each pipe direction is a bounded, flow-controlled byte stream. Inbound bytes queue here as
  // Uint8Array views over pooled C segments (we're the reader — the queue is just views+counters,
  // the bytes stay in C memory); outbound writes are partial (__hostData returns the accepted
  // count) and park for a drain when the host's buffer is full. All on the engine thread.
  function makePipe(id, command) {
    // inbound: queue of segment views drained through (head, headPos); qpos is a cursor, not
    // shift() (which memmoves the tail). One outstanding receive at a time (waiter) — either a
    // {buffer,offset,len} destination park (direct delivery) or a bare "readable" park (scanners).
    var queue = [], qpos = 0, head = null, headPos = 0, buffered = 0, waiter = null;
    var writeEnded = false, resetReason = null, readClosed = false, hostBlocked = false;
    // outbound: writer = the parked partial send {buffer, pos, end, resolve, reject}.
    var peerReadClosed = false, weEnded = false, writer = null;
    // buffered-WRITE staging: a fixed 8 KB segment coalescing small writes, allocated LAZILY so a
    // raw/read-only pipe pays nothing; wView writes fixed-width ints straight into it. lineSplit is
    // the native line splitter (reused across readUtf8Line). Reads need no staging — the scanners
    // work the inbound queue IN PLACE (it already IS the read buffer).
    var wbuf = null, wlen = 0, wView = null, lineSplit = null;
    function ensureWbuf() { if (wbuf === null) { wbuf = new Uint8Array(8192); wView = new DataView(wbuf.buffer); } }
    // 8-byte scratch for readInt/readLong (per-pipe: the receive loop holds it across an await, and a
    // pipe has one outstanding read, so it never overlaps itself).
    var numBuf = new Uint8Array(8), numView = new DataView(numBuf.buffer);

    function releaseInbound() {
      if (head) { __segRelease(head); head = null; }
      for (var i = qpos; i < queue.length; i++) __segRelease(queue[i]);
      queue.length = 0; qpos = 0; headPos = 0; buffered = 0;
    }

    // Advance head to the next unread segment; false when nothing is buffered.
    // Dequeue via cursor, not shift() (memmoves the tail on every pop) or delete (punches a hole,
    // which permanently de-optimizes the array to hash storage in QuickJS). Fully drained ->
    // truncate: drops all stale references in one shot and keeps the array's capacity. A long
    // stream read in small chunks may never fully drain, so also compact the dead prefix
    // occasionally — the live tail is bounded by the 64 KB cap (<= 9 entries), so it's trivial.
    function ensureHead() {
      if (head && headPos < head.length) return true;
      if (head) { __segRelease(head); head = null; headPos = 0; }
      if (qpos >= queue.length) return false;
      head = queue[qpos++]; headPos = 0;
      if (qpos === queue.length) { queue.length = 0; qpos = 0; }
      else if (qpos >= 64) { queue.splice(0, qpos); qpos = 0; }
      return true;
    }

    // Emit the drain the host writer is owed. Only ever called from read paths, never inside
    // _data (the critical-region rule).
    function drainCheck() {
      if (hostBlocked && buffered <= PIPE_LOW) { hostBlocked = false; __hostDrain(id); }
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

    // No more data either way — drop routing.
    function maybeReap() { if (weEnded && (writeEnded || readClosed)) delete pipes[id]; }

    // ---- core byte channel ----

    // undefined = fully accepted; a Promise = backpressure, resolved on DRAIN (buffer must stay
    // unmodified until then); -1 from the host (read closed) -> EPIPE.
    function send(buffer, offset, len) {
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
    }

    // Sync (a count, or -1 at EOF) when bytes are buffered or ended; a Promise only when it parks.
    // Throws the RESET reason.
    function receive(buffer, offset, len) {
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
    }

    // Parks for the NEXT delivery, not "is readable" — always async while the stream is live. The
    // scanners consume the current buffer before parking here; a resolve-on-any-data variant would
    // spin them. undefined once the write end has ended.
    function awaitMore() {
      if (readClosed) throw new Error("receive() after closeRead()");
      if (waiter) throw new Error("concurrent receive() on the same pipe");
      if (resetReason !== null) throw new Error(resetReason);
      if (writeEnded) return;
      return new Promise(function (resolve, reject) {
        waiter = { buffer: null, resolve: resolve, reject: reject };
      });
    }

    // Abandon the read side: release the segments and make the host's next send EPIPE.
    function closeRead() {
      if (readClosed) return;
      readClosed = true;
      releaseInbound();
      __hostCloseRead(id);
      if (waiter) { var w = waiter; waiter = null; w.reject(new Error("receive() after closeRead()")); } // in-flight receive is a misuse
      maybeReap();
    }

    // Write-side half-close (END if no reason, else RESET). The public close() flushes staged writes first.
    function halfClose(reason) {
      if (weEnded) return;
      weEnded = true;
      __hostClose(id, reason === undefined ? null : reason);
      maybeReap();
    }

    // ---- in-place scanners (drive readUtf8Line / readJson*; no copy out of C memory) ----

    // Consumes as it feeds, so an over-cap line can't deadlock the writer. 1 = complete, 0 = more, -1 = EOF.
    function lineFeed(l) {
      if (resetReason !== null) throw new Error(resetReason);
      while (ensureHead()) {
        var consumed = __linePush(l, head, headPos, head.length - headPos);
        if (consumed <= 0) throw new Error("__linePush made no progress"); // guard: a 0 spins the loop
        headPos += consumed; buffered -= consumed;
        if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
        if (__lineDone(l)) { drainCheck(); return 1; }
      }
      drainCheck();
      return writeEnded ? -1 : 0;
    }

    // 1 = complete (value's tail left queued), 0 = need more, -1 = EOF.
    function jsonFeed(p) {
      if (resetReason !== null) throw new Error(resetReason);
      while (ensureHead()) {
        var consumed = __jsonPush(p, head, headPos, head.length - headPos);
        if (__jsonDone(p)) { // may legitimately consume 0 here (value ended at the previous push)
          headPos += consumed; buffered -= consumed;
          if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
          drainCheck();
          return 1;
        }
        if (consumed <= 0) throw new Error("__jsonPush made no progress"); // guard: a 0 spins the loop
        headPos += consumed; buffered -= consumed;
        if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
      }
      drainCheck();
      return writeEnded ? -1 : 0;
    }

    // Fill dst[0..n) exactly, draining as it reads (so any n works — no cap deadlock); throws at EOF.
    async function readFully(dst, n) {
      var got = 0;
      while (got < n) {
        var r = receive(dst, got, n - got);
        var c = typeof r === "number" ? r : await r;
        if (c < 0) throw new Error("stream ended before " + n + " bytes");
        got += c;
      }
    }

    async function readJsonValue(expect) {
      var p = __jsonNew(expect);
      for (;;) {
        var s = jsonFeed(p);
        if (s !== 0) return __jsonEnd(p); // 1 = complete; -1 = EOF (finalize, or throw if incomplete)
        var w = awaitMore(); if (w) await w;
      }
    }

    // ---- buffered-write internals (into wbuf; undefined = sync, Promise = backpressure — leave the
    // staged segment unmodified until it resolves) ----

    function flushSink() {
      if (wlen === 0) return;
      var p = send(wbuf, 0, wlen);
      wlen = 0;
      return p;
    }

    function putByte(b) {
      if (wlen + 1 > wbuf.length) {
        var p = flushSink();
        if (p) return p.then(function () { wbuf[wlen++] = b; });
      }
      wbuf[wlen++] = b;
    }

    // >= a segment bypasses staging: stream to the pipe in segment-sized slices, paced by the reader.
    function writeBytesFn(b) {
      var n = b.length;
      if (n >= wbuf.length) {
        var off = 0;
        function pump() {
          while (off < n) {
            var m = Math.min(wbuf.length, n - off);
            var q = send(b, off, m);
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

    // __utf8Into encodes str[from,to) from flat storage, packing unitsRead|bytesWritten; fill ->
    // flush -> advance, one pass, memory bounded by the segment.
    function writeUtf8Native(str, from, to) {
      for (;;) {
        var rw = __utf8Into(str, from, to, wbuf, wlen);
        from += rw >>> 16;
        wlen += rw & 0xFFFF;
        if (from >= to) return;
        if (rw === 0 && wlen === 0) throw new Error("__utf8Into made no progress"); // guard on an empty segment
        var p = flushSink();
        if (p) {
          var at = from;
          return p.then(function () { return writeUtf8Native(str, at, to); });
        }
      }
    }

    // stream-encode the parts back-to-back (no `a + b + c` concat first)
    function writeUtf8Parts(args) {
      if (args.length === 1) { var s = args[0]; return writeUtf8Native(s, 0, s.length); }
      var k = 0;
      function next() {
        while (k < args.length) {
          var str = args[k++];
          var p = writeUtf8Native(str, 0, str.length);
          if (p) return p.then(next);
        }
      }
      return next();
    }

    return {
      id: id,
      command: command,
      send: send,
      receive: receive,
      awaitMore: awaitMore,
      closeRead: closeRead,
      // flush staged writes, then write-side half-close (END, or RESET with a reason)
      close: function (reason) {
        var p = flushSink();
        if (p) return p.then(function () { halfClose(reason); });
        halfClose(reason);
      },
      flush: flushSink,

      // ---- buffered reads, in place over the inbound queue (no second buffer) ----
      async readInt() { await readFully(numBuf, 4); return numView.getInt32(0); },
      async readLong() { await readFully(numBuf, 8); return Number(numView.getBigInt64(0)); }, // number: loses precision above 2^53
      // strict requires a '\n' (throws at EOF); else the final unterminated line, then null at EOF
      async readUtf8Line(strict) {
        if (lineSplit === null) lineSplit = __lineNew();
        for (;;) {
          var s = lineFeed(lineSplit);
          if (s === 1) return __lineEnd(lineSplit);
          if (s === -1) {
            var last = __lineEnd(lineSplit);
            if (strict) throw new Error("readUtf8Line(strict): stream exhausted before a newline");
            return last;
          }
          var w = awaitMore(); if (w) await w;
        }
      },
      // stops at the value's end, leaving trailing bytes queued; root type checked on the first byte
      readJsonObject: function () { return readJsonValue(1); },
      readJsonArray: function () { return readJsonValue(2); },
      async readUtf8() {
        var out = new Uint8Array(8192), size = 0;
        for (;;) {
          if (size === out.length) { var grown = new Uint8Array(out.length * 2); grown.set(out); out = grown; }
          var r = receive(out, size, out.length - size);
          var n = typeof r === "number" ? r : await r;
          if (n < 0) break;
          size += n;
        }
        return __utf8Decode(out, 0, size);
      },

      // ---- buffered writes: undefined = done synchronously, a Promise = backpressure; sequential ----
      writeBytes: function (b) { ensureWbuf(); return writeBytesFn(b); },
      // out-of-range throws rather than silently wrapping (DataView truncates mod 2^n)
      writeInt: function (v) {
        if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647)
          throw new RangeError("writeInt: " + v + " is not a signed 32-bit integer");
        ensureWbuf();
        if (wlen + 4 > wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wView.setInt32(wlen, v); wlen += 4; });
        }
        wView.setInt32(wlen, v); wlen += 4;
      },
      writeLong: function (v) {
        var b = typeof v === "bigint" ? v : BigInt(Math.trunc(v));
        if (b < -0x8000000000000000n || b > 0x7fffffffffffffffn)
          throw new RangeError("writeLong: " + b + " does not fit in a signed 64-bit long");
        ensureWbuf();
        if (wlen + 8 > wbuf.length) {
          var p = flushSink();
          if (p) return p.then(function () { wView.setBigInt64(wlen, b); wlen += 8; });
        }
        wView.setBigInt64(wlen, b); wlen += 8;
      },
      writeUtf8: function () { ensureWbuf(); return writeUtf8Parts(arguments); },
      writeUtf8Range: function (str, from, to) { ensureWbuf(); return writeUtf8Native(str, from, to); },
      writeUtf8Line: function () {
        ensureWbuf();
        var p = writeUtf8Parts(arguments);
        return p ? p.then(function () { return putByte(10); }) : putByte(10);
      },
      // matches JSON.stringify (a cycle throws TypeError); streams via the native encoder, never
      // materializing the whole JSON string
      writeObjectAsJson: function (value) {
        ensureWbuf();
        var enc = __jsonEncNew(value);
        function pump() {
          for (;;) {
            wlen += __jsonEncFill(enc, wbuf, wlen);
            if (__jsonEncDone(enc)) return;
            var p = flushSink();
            if (p) return p.then(pump);
          }
        }
        return pump();
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
        var w = waiter, delivered = 0;
        if (w !== null) {
          waiter = null;
          if (w.buffer) { // destination park: direct delivery (the queue is empty by construction)
            delivered = Math.min(len, w.len);
            if (delivered === len) w.buffer.set(u8, w.offset);
            else w.buffer.set(u8.subarray(0, delivered), w.offset);
            pos = delivered;
          }
        }
        try {
          while (pos < len && buffered < PIPE_CAP) {
            var seg = __segTake(u8, pos, Math.min(len - pos, PIPE_CAP - buffered));
            queue.push(seg);
            buffered += seg.length;
            pos += seg.length;
          }
        } catch (e) {
          // Buffering failed — a segment-pool malloc OOM is the expected cause (we're below the 64 KB
          // cap, so __segTake is allocating), but surface whatever actually threw rather than presume.
          // Not recoverable mid-stream: don't swallow it or fake backpressure. Poison the read side so
          // this and every later receive() throws, fail the parked reader, and report EPIPE to the
          // host writer, so both ends fail consistently. Under a JNI critical region here — local
          // state + a promise reject only, never a __host* call.
          resetReason = "pipe buffering failed: " + ((e && e.message) || e);
          releaseInbound();
          if (w !== null) w.reject(new Error(resetReason));
          return -1;
        }
        // Wake the reader LAST, once the queue is fully consistent (nothing depends on the resolution
        // being deferred to a microtask).
        if (pos < len) hostBlocked = true; // buffer full: owe the host a DRAIN once we read below LOW
        if (w !== null) {
          if (w.buffer) w.resolve(delivered);
          else w.resolve(); // readable park: the bytes are queued now; the scanner rescans
        }
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

      // Flow control from the host: its buffer drained to the low-water mark; continue the parked
      // partial send from where it stopped.
      _drain: function () {
        var w = writer;
        if (!w) return;
        var written = __hostData(id, w.buffer, w.pos, w.end - w.pos);
        if (written < 0) { writer = null; peerReadClosed = true; w.reject(brokenPipe()); return; }
        w.pos += written;
        if (w.pos >= w.end) { writer = null; w.resolve(); }
        // else stay parked for the next drain
      }
    };
  }

  // Kotlin opened a pipe to us. Returns 0 (accepted) or -1 (refused: no handler) — a refusal
  // fails Kotlin's openPipe() itself; no pipe is created on either side.
  globalThis.__jsOpen = function (id, command) {
    var handler = accepts[command];
    if (!handler) return -1;
    var pipe = makePipe(id, command);
    pipes[id] = pipe;
    Promise.resolve().then(function () { return handler(pipe); })
      .catch(function (e) { pipe.closeRead(); pipe.close(String((e && e.message) || e)); }); // RESET: surface the failure
    return 0;
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
  // Kotlin flow-control signal: its buffer drained (continue the parked partial send).
  globalThis.__jsDrain = function (id) {
    var p = pipes[id]; if (p) p._drain();
  };

  globalThis.ymix = {
    // Open a pipe to the host. Fails AT THE OPEN — like ECONNREFUSED — when nothing serves the
    // command: __hostOpen returns a C-style code (0 accepted; -1 refused / host callback failed;
    // -2 called under a critical region, a bootstrap bug), and a failed open leaves no registry
    // entry behind on either side.
    open: function (command) {
      var id = nextId; nextId += 2;
      var pipe = makePipe(id, command);
      pipes[id] = pipe; // registered first so anything crossing during the call finds the pipe
      var rc = __hostOpen(id, command);
      if (rc < 0) {
        delete pipes[id];
        throw new Error(rc === -2 ? 'open("' + command + '") during data delivery'
                                  : 'open("' + command + '") refused by host');
      }
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
  };
})();

// ---- Host-scheduled timers: setTimeout/setInterval over the pipe protocol (see Timers.kt). A
// cleared timer's async loop ends when the host closes the pipe (receive -> EOF). A callback that
// throws rejects this async task and, for setInterval, stops the interval.
(function () {
  function startTimer(cb, ms, command, repeat, args) {
    var pipe = ymix.open(command);
    // Delay as a big-endian 8-byte long — fixed width self-frames it (Kotlin reads it with okio
    // readLong).
    pipe.writeLong(Math.max(0, Math.floor(Number(ms) || 0)));
    pipe.flush();
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
