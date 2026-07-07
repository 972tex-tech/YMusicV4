// The engine bootstrap: the whole pipe protocol + streaming JSON + host-scheduled timers, built on
// the transport entry points (__jsOpen/__jsData/__jsClose/__jsCloseRead/__jsDrain in;
// __hostOpen/__hostData/__hostClose/__hostCloseRead/__hostDrain out) and the native pipe/JSON
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

  var pipes = {};
  var accepts = {};
  var nextId = 2; // JS opens even ids; Kotlin opens odd.
  var PIPE_CAP = 65536, PIPE_LOW = 32768; // per-direction buffer bound (must match QuickJsRuntime.kt)
  var EOF = -1, WOULD_BLOCK = -2;         // take(): EOF is the public receive() value; WOULD_BLOCK is internal
  function brokenPipe() { return new Error("EPIPE: broken pipe"); }
  // One shared scratch for every fixed-width int read, across all pipes. Safe despite the pipe
  // multiplexing: the buffered source's readInt/readLong await request(n) FIRST, then copy out and
  // decode in a synchronous tail — nothing yields while holding intBuf, and single-threaded
  // microtasks never interleave two such tails.
  var intBuf = new Uint8Array(8), intView = new DataView(intBuf.buffer);

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

    return {
      id: id,
      command: command,
      // Bytes currently buffered (not yet read) — the buffered source's request(n) polls this before
      // a synchronous fixed-width copy-out.
      available: function () { return buffered; },

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

      // Park for the NEXT delivery beyond what's already queued (NOT "is data readable"): always
      // async when the stream is live, regardless of buffered bytes. Callers must have already
      // consumed/counted the current buffer — scanners consume it first, requestN guards with
      // available() < n — otherwise a "resolve if any data" variant would spin them. No wakeup is
      // lost: single-threaded, so nothing arrives between a caller's scan and its park. Returns
      // undefined once the write end has ended (the caller rescans the final bytes), else a Promise.
      awaitMore: function () {
        if (readClosed) throw new Error("receive() after closeRead()");
        if (waiter) throw new Error("concurrent receive() on the same pipe");
        if (resetReason !== null) throw new Error(resetReason);
        if (writeEnded) return;
        return new Promise(function (resolve, reject) {
          waiter = { buffer: null, resolve: resolve, reject: reject };
        });
      },

      // ---- in-place scanners (the buffered source drives these; no copy out of C memory) ----

      // Feed all buffered bytes to a line splitter (__lineNew), segment by segment, consuming
      // exactly what it takes — a completed line leaves its tail queued, and because bytes are
      // consumed as fed (drainCheck fires), an over-cap line can never deadlock the writer: a
      // spanning line simply accumulates inside the splitter. Returns 1 when a line is complete,
      // 0 when more input is needed, -1 at EOF (caller finalizes via __lineEnd -> string | null).
      _lineFeed: function (l) {
        if (resetReason !== null) throw new Error(resetReason);
        while (ensureHead()) {
          var consumed = __linePush(l, head, headPos, head.length - headPos);
          // __linePush consumes all of len (no '\n') or j+1 >= 1 (found one) — guard the invariant:
          // a 0 here would otherwise spin this loop forever on the engine thread
          if (consumed <= 0) throw new Error("__linePush made no progress");
          headPos += consumed; buffered -= consumed;
          if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
          if (__lineDone(l)) { drainCheck(); return 1; }
        }
        drainCheck();
        return writeEnded ? -1 : 0;
      },

      // Feed all buffered bytes to a streaming JSON parser, segment by segment, consuming exactly
      // what it takes (a completed value leaves its tail queued). Returns 1 when the value is
      // complete, 0 when more input is needed, -1 at EOF (caller finalizes -> value or throw).
      _jsonFeed: function (p) {
        if (resetReason !== null) throw new Error(resetReason);
        while (ensureHead()) {
          var consumed = __jsonPush(p, head, headPos, head.length - headPos);
          if (__jsonDone(p)) { // may legitimately consume 0 here (value ended at the previous push)
            headPos += consumed; buffered -= consumed;
            if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
            drainCheck();
            return 1;
          }
          // ensureHead guarantees len >= 1, and an undone parser must consume everything fed —
          // guard the invariant: a 0 here would otherwise spin this loop forever on the engine thread
          if (consumed <= 0) throw new Error("__jsonPush made no progress");
          headPos += consumed; buffered -= consumed;
          if (headPos >= head.length) { __segRelease(head); head = null; headPos = 0; }
        }
        drainCheck();
        return writeEnded ? -1 : 0;
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
      var wView = new DataView(wbuf.buffer); // fixed-width int writes land straight in the staging buffer
      // line splitter (native, __lineNew) — one per wrapper, reused across readUtf8Line calls
      var lineSplit = null;

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

      // Encode str[from, to) straight into the segment: one native call per segment-full
      // (__utf8Into encodes directly from flat string storage and packs unitsRead/bytesWritten),
      // then fill -> flush -> advance `from`. ONE encode pass total with memory bounded by the
      // segment no matter how big the string — nothing is ever materialized per call.
      function writeUtf8Native(str, from, to) {
        for (;;) {
          var rw = __utf8Into(str, from, to, wbuf, wlen);
          from += rw >>> 16;
          wlen += rw & 0xFFFF;
          if (from >= to) return;
          // more to encode: the segment is (nearly) full. A whole EMPTY segment always fits at
          // least one codepoint, so a zero-progress round on an empty buffer can only mean a
          // broken native — guard it (same invariant rule as the feed loops).
          if (rw === 0 && wlen === 0) throw new Error("__utf8Into made no progress");
          var p = flushSink();
          if (p) {
            var at = from;
            return p.then(function () { return writeUtf8Native(str, at, to); });
          }
        }
      }

      // Park (without consuming) until at least n bytes are buffered; false if the stream ends first.
      // Backs the fixed-width reads: they await this, then copy out synchronously (see intBuf).
      async function requestN(n) {
        while (pipe.available() < n) {
          var w = pipe.awaitMore();
          if (!w) return pipe.available() >= n; // stream ended
          await w;
        }
        return true;
      }

      return {
        // ---- BufferedSource (in-place over the pipe's segment queue) ----
        // Read up to len bytes into buffer at offset; count, or -1 at EOF (pipe.receive semantics —
        // synchronous when bytes are buffered, direct-delivery rendezvous when parked).
        read(buffer, offset, len) {
          return pipe.receive(buffer, offset, len);
        },
        // Fixed-width big-endian integers, the read mirror of writeInt/writeLong: block until the full
        // 4/8 bytes arrive (throw on a short stream), then copy out + decode synchronously (the shared
        // intBuf is safe only because nothing yields between here and the return). readLong returns a
        // JS number (safe-integer range — the timer delay and typical length fields).
        async readInt() {
          if (!(await requestN(4))) throw new Error("readInt: stream ended before 4 bytes");
          pipe.receive(intBuf, 0, 4); // synchronous: >= 4 buffered, nothing consumed since requestN
          return intView.getInt32(0);
        },
        async readLong() {
          if (!(await requestN(8))) throw new Error("readLong: stream ended before 8 bytes");
          pipe.receive(intBuf, 0, 8);
          return Number(intView.getBigInt64(0));
        },
        // One line up to '\n' (trailing '\r' stripped), decoded natively straight from the queued
        // segments. Non-strict (default): a final line without a '\n' is returned, and a clean EOF
        // returns null. strict=true (okio readUtf8LineStrict): requires a '\n' — throws at EOF.
        async readUtf8Line(strict) {
          if (lineSplit === null) lineSplit = __lineNew(); // lazily created, reused across lines
          for (;;) {
            var s = pipe._lineFeed(lineSplit);
            if (s === 1) return __lineEnd(lineSplit); // complete line ('\n' consumed, '\r' stripped)
            if (s === -1) {
              var last = __lineEnd(lineSplit); // the partial remainder (string) or null; also resets
              if (strict) throw new Error("readUtf8Line(strict): stream exhausted before a newline");
              return last;
            }
            var w = pipe.awaitMore(); if (w) await w;
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
            var w = pipe.awaitMore(); if (w) await w;
          }
        },
        // All remaining bytes to EOF, decoded as UTF-8 in ONE pass at the end (multi-byte chars can
        // straddle chunk boundaries, so the bytes must be complete before decoding). Plain
        // consuming reads into one growing buffer — a parked read gets rendezvous delivery straight
        // into it — decoded in place by range (no exact-size copy). This is the module's only
        // whole-body materializer; prefer the streaming readers when the shape allows.
        async readUtf8() {
          var out = new Uint8Array(8192), size = 0;
          for (;;) {
            if (size === out.length) {
              var grown = new Uint8Array(out.length * 2);
              grown.set(out);
              out = grown;
            }
            // receive is await-optional: a number when bytes were already buffered (skip the
            // microtask hop), a Promise only when it actually parks
            var r = pipe.receive(out, size, out.length - size);
            var n = typeof r === "number" ? r : await r;
            if (n < 0) break;
            size += n;
          }
          return __utf8Decode(out, 0, size);
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
        // Fixed-width big-endian integers (okio Buffer.writeInt/writeLong byte order), for length
        // prefixes and self-framing headers. writeLong takes a JS number (safe-integer range) or a
        // BigInt. Out-of-range values throw rather than silently wrap (DataView truncates mod 2^n),
        // so a too-big long is caught at the call, not shipped as garbage. Coalesced like any small
        // write — the sequential-write rule still applies.
        writeInt(v) {
          if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647)
            throw new RangeError("writeInt: " + v + " is not a signed 32-bit integer");
          if (wlen + 4 > wbuf.length) {
            var p = flushSink();
            if (p) return p.then(function () { wView.setInt32(wlen, v); wlen += 4; });
          }
          wView.setInt32(wlen, v); wlen += 4;
        },
        writeLong(v) {
          var b = typeof v === "bigint" ? v : BigInt(Math.trunc(v));
          if (b < -0x8000000000000000n || b > 0x7fffffffffffffffn)
            throw new RangeError("writeLong: " + b + " does not fit in a signed 64-bit long");
          if (wlen + 8 > wbuf.length) {
            var p = flushSink();
            if (p) return p.then(function () { wView.setBigInt64(wlen, b); wlen += 8; });
          }
          wView.setBigInt64(wlen, b); wlen += 8;
        },
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
        // Serialize value to JSON via the resumable NATIVE encoder: __jsonEncFill fills the sink
        // segment at C speed — one native call per ~8 KB of output, not per token — and flushSink
        // carries backpressure between fills. Never builds the whole JSON string or a Uint8Array
        // (unlike __stringifyJson); memory = one segment + the encoder's walk stack + one in-flight
        // token. The encoder object is freed by GC (abandonment-safe). Matches JSON.stringify
        // (toJSON, key order, escapes, NaN/undefined rules; a cycle throws TypeError). For big
        // outbound data prefer JSONL (one writeObjectAsJson per line) so peak memory stays ~one item.
        writeObjectAsJson(value) {
          var enc = __jsonEncNew(value);
          function pump() {
            for (;;) {
              wlen += __jsonEncFill(enc, wbuf, wlen);   // fill the segment at C speed
              if (__jsonEncDone(enc)) return;           // fully emitted
              var p = flushSink();                      // segment full -> existing backpressure
              if (p) return p.then(pump);
            }
          }
          return pump();
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
    // Delay as a big-endian 8-byte long — fixed width self-frames it (Kotlin reads it with okio
    // readLong); io is discarded right after, its 8 bytes copied out by flush().
    var io = ymix.buffered(pipe);
    io.writeLong(Math.max(0, Math.floor(Number(ms) || 0)));
    io.flush();
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
