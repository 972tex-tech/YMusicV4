// The engine bootstrap: the pipe protocol's JS half + host-scheduled timers, built on the
// transport entry points (__jsOpen/__jsClose/__jsCloseRead/__jsDrain in; __hostOpen/__hostData/
// __hostClose/__hostCloseRead out) and the C pipe buffer (__pb*, see quickjs_pipe.h). Minified at
// build time into a generated Kotlin const the engine evals (see build.gradle.kts).
//
//  - ymix.open(command) -> a pipe to Kotlin (this side is the client).
//  - ymix.onAccept(command, async (pipe) => ...) -> handle pipes Kotlin opens to us (server side).
//  - a pipe is the whole okio-Buffer-style object: the raw channel is send(buf,off,len) /
//    receive(buf,off,len) (-> count or -1); buffered reads (readInt/readLong, readUtf8,
//    readUtf8Line, readJsonObject/readJsonArray); buffered writes coalescing through a
//    lazily-allocated staging segment (writeInt/writeLong, writeBytes, writeUtf8[Range|Line],
//    writeObjectAsJson, flush()); then close(reason?) (flushes first) / closeRead() / .command.
// Kotlin opens odd pipe ids, JS opens even ones, so the two id spaces never collide.
//
// MEMORY MODEL (see PIPE_STREAMING.md): the whole inbound direction lives in C — host writes go
// straight into pooled C segments (never through JS), and every read is a thin native that
// returns the result when it is already buffered or a Promise that C settles on a later write /
// EOF / RESET. Hot callers can skip the microtask hop with
// `var r = pipe.readX(); typeof r === "..." ? r : await r`. Only the OUTBOUND half is JS: the
// staging segment, the partial-send park, and the DRAIN/EPIPE bookkeeping below.

(function () {
  // Encode one or more strings to UTF-8, concatenated. A single string delegates to the native
  // one-shot; the multi-arg form (utf8Encode(str, "\n")) stream-encodes here to avoid a JS string
  // concat first — QuickJS strings are immutable, so `a + b` would allocate and copy.
  function utf8Encode() {
    if (arguments.length === 1) { var s = arguments[0]; return __utf8Encode(s, 0, s.length); }
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
  function brokenPipe() { return new Error("EPIPE: broken pipe"); }

  function makePipe(id, command) {
    var pb = __pbNew(id); // the C-owned inbound buffer; reads delegate to it
    var writeEnded = false, readClosed = false; // routing bookkeeping only — buffer state is C's
    // outbound: writer = the parked partial send {buffer, pos, end, resolve, reject}.
    var peerReadClosed = false, weEnded = false, writer = null;
    // buffered-WRITE staging: a fixed 8 KB segment coalescing small writes, allocated LAZILY so a
    // raw/read-only pipe pays nothing; wView writes fixed-width ints straight into it.
    var wbuf = null, wlen = 0, wView = null;
    function ensureWbuf() { if (wbuf === null) { wbuf = new Uint8Array(8192); wView = new DataView(wbuf.buffer); } }
    // One outstanding write/flush/close at a time, ENFORCED at the public boundary (mirrors the
    // read side's concurrent-receive throw): every sink op passes its result through writeLock(), which
    // claims the pipe while an async op is in flight; a second op then throws instead of silently
    // interleaving mutations of the staging segment. Internal continuations call the closure
    // functions directly and are not re-guarded.
    var wbusy = false;
    function checkWriteBusy() { if (wbusy) throw new Error("concurrent write on the same pipe"); }
    function writeLock(r) {
      if (r === undefined) return;
      wbusy = true;
      return r.then(function (v) { wbusy = false; return v; },
                    function (e) { wbusy = false; throw e; });
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

    // Abandon the read side: release the buffer and make the host's next send EPIPE.
    function closeRead() {
      if (readClosed) return;
      readClosed = true;
      __pbCloseRead(pb);
      __hostCloseRead(id);
      maybeReap();
    }

    // Write-side half-close (END if no reason, else RESET). The public close() flushes staged writes first.
    function closeWrite(reason) {
      if (weEnded) return;
      weEnded = true;
      __hostClose(id, reason === undefined ? null : reason);
      maybeReap();
    }

    // ---- buffered-write internals (into wbuf; undefined = sync, Promise = backpressure — leave the
    // staged segment unmodified until it resolves) ----

    // wlen stays claimed until a parked send completes: a non-awaiting caller then appends beyond
    // the in-flight window (and fails on the concurrent-send guard) instead of corrupting it.
    function flushSink() {
      if (wlen === 0) return;
      var p = send(wbuf, 0, wlen);
      if (!p) { wlen = 0; return; }
      return p.then(function () { wlen = 0; });
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

    // (to - from) * 3 bounds the encoded size (the UTF-16 worst case), deciding fit up front:
    // a string that fits coalesces into the staging segment (__utf8Into, no allocation); one too
    // big for a whole segment is encoded once in C (__utf8Encode, exact-size zero-copy buffer)
    // and streamed by send() under the normal backpressure.
    function writeUtf8Native(str, from, to) {
      var max = (to - from) * 3;
      if (max > wbuf.length - wlen) {
        var p = flushSink();
        if (p) return p.then(function () { return writeUtf8Native(str, from, to); });
      }
      if (max <= wbuf.length - wlen) { wlen += __utf8Into(str, from, to, wbuf, wlen); return; }
      return send(__utf8Encode(str, from, to));
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
      send: function (buffer, offset, len) { checkWriteBusy(); return writeLock(send(buffer, offset, len)); },
      closeRead: closeRead,
      // No reason: flush staged writes, then half-close (END). With a reason: RESET = ABORT — no
      // flush (the peer discards buffered data on reset anyway), allowed mid-write, and a parked
      // send is failed rather than left hanging.
      close: function (reason) {
        if (reason !== undefined) {
          wlen = 0;
          var w = writer;
          if (w) { writer = null; w.reject(new Error("send aborted: " + reason)); }
          closeWrite(reason);
          return;
        }
        checkWriteBusy();
        var p = flushSink();
        if (p) return writeLock(p.then(function () { closeWrite(reason); }));
        closeWrite(reason);
      },
      flush: function () { checkWriteBusy(); return writeLock(flushSink()); },

      // ---- reads (C-owned buffer; value when already buffered, else a Promise C settles) ----
      receive: function (buffer, offset, len) {
        var o = offset || 0;
        return __pbRecv(pb, buffer, o, len === undefined ? buffer.length - o : len);
      },
      readInt: function () { return __pbInt(pb); },
      readLong: function () { return __pbLong(pb); }, // number: loses precision above 2^53
      // strict requires a '\n' (throws at EOF); else the final unterminated line, then null at EOF
      readUtf8Line: function (strict) { return __pbLine(pb, !!strict); },
      // stops at the value's end, leaving trailing bytes queued; root type checked on the first byte
      readJsonObject: function () { return __pbJson(pb, 1); },
      readJsonArray: function () { return __pbJson(pb, 2); },
      readUtf8: function () { return __pbUtf8(pb); },

      // ---- buffered writes: undefined = done synchronously, a Promise = backpressure ----
      writeBytes: function (b) { checkWriteBusy(); ensureWbuf(); return writeLock(writeBytesFn(b)); },
      // out-of-range throws rather than silently wrapping (DataView truncates mod 2^n)
      writeInt: function (v) {
        checkWriteBusy();
        if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647)
          throw new RangeError("writeInt: " + v + " is not a signed 32-bit integer");
        ensureWbuf();
        if (wlen + 4 > wbuf.length) {
          var p = flushSink();
          if (p) return writeLock(p.then(function () { wView.setInt32(wlen, v); wlen += 4; }));
        }
        wView.setInt32(wlen, v); wlen += 4;
      },
      writeLong: function (v) {
        checkWriteBusy();
        var b = typeof v === "bigint" ? v : BigInt(Math.trunc(v));
        if (b < -0x8000000000000000n || b > 0x7fffffffffffffffn)
          throw new RangeError("writeLong: " + b + " does not fit in a signed 64-bit long");
        ensureWbuf();
        if (wlen + 8 > wbuf.length) {
          var p = flushSink();
          if (p) return writeLock(p.then(function () { wView.setBigInt64(wlen, b); wlen += 8; }));
        }
        wView.setBigInt64(wlen, b); wlen += 8;
      },
      writeUtf8: function () { checkWriteBusy(); ensureWbuf(); return writeLock(writeUtf8Parts(arguments)); },
      writeUtf8Range: function (str, from, to) { checkWriteBusy(); ensureWbuf(); return writeLock(writeUtf8Native(str, from, to)); },
      writeUtf8Line: function () {
        checkWriteBusy();
        ensureWbuf();
        var p = writeUtf8Parts(arguments);
        return writeLock(p ? p.then(function () { return putByte(10); }) : putByte(10));
      },
      // matches JSON.stringify (a cycle throws TypeError); streams via the native encoder, never
      // materializing the whole JSON string
      writeObjectAsJson: function (value) {
        checkWriteBusy();
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
        return writeLock(pump());
      },

      // ---- transport entry points (host -> us; the data path never comes through JS) ----

      // Host closed its write end (C already delivered EOF/RESET to the buffer).
      _end: function () {
        writeEnded = true;
        maybeReap();
      },

      // A refused ymix.open: drop the C registration without touching the host (no pipe exists there).
      _abandon: function () {
        readClosed = true;
        __pbCloseRead(pb);
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
  // Kotlin closed its write end (END or RESET — the C buffer already knows which).
  globalThis.__jsClose = function (id) {
    var p = pipes[id]; if (p) p._end();
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
        pipe._abandon();
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
