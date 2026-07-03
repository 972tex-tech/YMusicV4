package ymusic.quickjs

/**
 * JS injected once at engine start. It builds the whole pipe protocol on top of six transport
 * entry points — three the host calls into JS (`__jsOpen/__jsData/__jsClose`) and three JS calls
 * into the host (`__hostOpen/__hostData/__hostClose`). No framing/header: id is an argument,
 * command/reason are native strings, payloads are passed directly.
 *
 *  - `ymix.open(command)` -> a [pipe] to Kotlin (this side is the client).
 *  - `ymix.onAccept(command, async (pipe) => ...)` -> handle pipes Kotlin opens to us (server side).
 *    Routed by the exact command name; arguments are read from the pipe's frames in the handler.
 *  - a pipe has `send(buf,off,len)`, `await receive(buf,off,len)` (-> count or -1), `close()`, `.command`.
 *  - `ymix.utf8Encode/utf8Decode` for text<->bytes (QuickJS has no TextEncoder/TextDecoder).
 *
 * Kotlin opens odd pipe ids, JS opens even ones, so the two id spaces never collide. Close carries
 * a reason: null = normal half-close (END), a string = error (RESET). Uses no Kotlin `$` interp.
 */
internal const val BOOTSTRAP_JS = """
(function () {
  // Encode one or more strings to UTF-8, concatenated. Multi-arg (utf8Encode(str, "\n")) avoids a
  // JS string concat first — QuickJS strings are immutable, so `a + b` would allocate and copy.
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

  function utf8Decode(bytes) {
    var out = "", i = 0, n = bytes.length;
    while (i < n) {
      var c = bytes[i], cp, adv;
      if (c < 0x80) { cp = c; adv = 1; }
      else if ((c & 0xE0) === 0xC0) { cp = ((c & 0x1F) << 6) | (bytes[i + 1] & 0x3F); adv = 2; }
      else if ((c & 0xF0) === 0xE0) { cp = ((c & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F); adv = 3; }
      else if ((c & 0xF8) === 0xF0) { cp = ((c & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F); adv = 4; }
      else { cp = 0xFFFD; adv = 1; }
      out += String.fromCodePoint(cp);
      i += adv;
    }
    return out;
  }

  var pipes = {};
  var accepts = {};
  var nextId = 2; // JS opens even ids; Kotlin opens odd.

  // A pipe is a byte stream: arrived payloads queue up and are drained through (head, headPos).
  function makePipe(id, command) {
    // queue is drained via qpos (cursor), not shift() — shift() memmoves the tail each call.
    // One outstanding receive at a time (waiter), not a queue: overlapping receives are a bug.
    var queue = [], qpos = 0, waiter = null, head = null, headPos = 0, ended = false, weClosed = false;

    function serve() {
      if (!waiter) return;
      if (!head || headPos >= head.length) {
        if (qpos < queue.length) {
          head = queue[qpos++]; headPos = 0;
          if (qpos === queue.length) { queue.length = 0; qpos = 0; }
        } else if (ended) { var we = waiter; waiter = null; we.resolve(-1); return; }
        else return;
      }
      var w = waiter; waiter = null;
      var n = Math.min(w.len, head.length - headPos);
      w.buffer.set(head.subarray(headPos, headPos + n), w.offset);
      headPos += n;
      w.resolve(n);
    }

    return {
      id: id,
      command: command,
      // send buffer[offset, offset+len) — copy-first (__hostData copies the bytes synchronously).
      send: function (buffer, offset, len) {
        var o = offset || 0;
        var l = (len === undefined ? buffer.length - o : len);
        if (l > 0) __hostData(id, buffer, o, l);
      },
      // receive up to len bytes into buffer at offset; resolves with count, or -1 at EOF.
      receive: function (buffer, offset, len) {
        var o = offset || 0;
        var l = (len === undefined ? buffer.length - o : len);
        return new Promise(function (resolve, reject) {
          if (l === 0) { resolve(0); return; }
          if (waiter) { reject(new Error("concurrent receive() on the same pipe")); return; }
          waiter = { buffer: buffer, offset: o, len: l, resolve: resolve };
          serve();
        });
      },
      // Half-close: END means "done sending"; keep receiving until the peer ends.
      close: function () { if (!weClosed) { weClosed = true; __hostClose(id, null); } },
      _data: function (bytes) { queue.push(bytes); serve(); },
      _end: function () { ended = true; serve(); delete pipes[id]; }
    };
  }

  // Kotlin opened a pipe to us.
  globalThis.__jsOpen = function (id, command) {
    var pipe = makePipe(id, command);
    pipes[id] = pipe;
    var handler = accepts[command];
    if (handler) Promise.resolve().then(function () { return handler(pipe); })
      .catch(function (err) { __hostClose(id, String(err && err.message || err || "handler error")); delete pipes[id]; });
    else { __hostClose(id, "no handler: " + command); delete pipes[id]; }
  };
  // Kotlin sent bytes. `ab` is this call's own fresh ArrayBuffer — safe to keep as a view, no copy.
  globalThis.__jsData = function (id, ab) {
    var p = pipes[id]; if (p) p._data(new Uint8Array(ab));
  };
  // Kotlin closed (reason null = END, string = RESET) — either way the peer sends no more.
  globalThis.__jsClose = function (id, reason) {
    var p = pipes[id]; if (p) p._end();
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
    utf8Decode: utf8Decode,
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
      // sink buffer: writes accumulate in wbuf[0, wlen) until flush()/close() sends them
      var wbuf = new Uint8Array(4096), wlen = 0;

      async function fill() {
        if (reof) return 0;
        if (rbeg > 0) { rbuf.copyWithin(0, rbeg, rend); rend -= rbeg; rbeg = 0; }
        if (rend >= rbuf.length) { var nb = new Uint8Array(rbuf.length * 2); nb.set(rbuf); rbuf = nb; } // full: rend === length
        var n = await pipe.receive(rbuf, rend, rbuf.length - rend);
        if (n < 0) { reof = true; return 0; }
        rend += n; return n;
      }
      function ensureW(n) {
        if (wlen + n > wbuf.length) {
          var cap = wbuf.length * 2; while (cap < wlen + n) cap *= 2;
          var nb = new Uint8Array(cap); nb.set(wbuf.subarray(0, wlen)); wbuf = nb;
        }
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
                var line = ymix.utf8Decode(rbuf.subarray(rbeg, e));
                rbeg = i + 1;
                return line;
              }
            }
            scanned = rend - rbeg;
            if ((await fill()) === 0) {
              if (strict) throw new Error("readUtf8Line(strict): stream exhausted before a newline");
              if (rend > rbeg) { var l = ymix.utf8Decode(rbuf.subarray(rbeg, rend)); rbeg = rend; return l; }
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
        // All remaining bytes, decoded as UTF-8.
        async readUtf8() {
          return ymix.utf8Decode(await this.readByteArray());
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

        // ---- BufferedSink: writes accumulate until an explicit flush()/close() sends them ----
        writeBytes(b) { ensureW(b.length); wbuf.set(b, wlen); wlen += b.length; return this; },
        writeUtf8(str) { return this.writeBytes(ymix.utf8Encode(str)); },
        // encode str + '\n' in one pass (no string concat, one append)
        writeUtf8Line(str) { return this.writeBytes(ymix.utf8Encode(str, "\n")); },
        // sync: pipe.send is synchronous (copy-first), so flush/close have nothing to await
        flush() { if (wlen > 0) { pipe.send(wbuf, 0, wlen); wlen = 0; } },
        close() { this.flush(); pipe.close(); }
      };
    }
  };
})();
"""
