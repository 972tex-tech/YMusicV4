// HttpRequest — a small OkHttp-`Request.Builder`-shaped fluent client over the :quickjs pipe
// protocol (consistent with :kmpHttp, which ports the OkHttp API). Start a builder with
// HttpRequest.get(url) / .post(url) / .put(url) / .delete(url) / .patch(url) / .head(url) /
// .method(m, url); chain .setHeader() (replace) / .addHeader() (append) / .headers() (bulk set) —
// all throw on CR/LF and bad names — or .setHeaderSanitized()/.addHeaderSanitized() for untrusted
// values (coerce instead of throw); set a body with .body() or .json(); set per-request options
// with .timeout(ms) / .followRedirects(bool); then .build() -> HttpCall — call.execute() resolves
// the Response (once), call.cancel() aborts at ANY stage:
//   HttpRequest.get(url).setHeader("User-Agent", "x").build().execute()  -> Response
// A body is one of:
//   - a string          (Content-Length set automatically, computed without encoding)
//   - a Uint8Array      (Content-Length set from .length)
//   - an (io) => …       callback handed the body pipe's buffered sink (writeUtf8/writeBytes/
//                        writeObjectAsJson/…, all streaming) — length unknown so no Content-Length
// .json(obj) is sugar for a callback body writing the object as JSON + a default Content-Type.
// Response: status / statusText / ok / url (final, after redirects) /
// headers.get(name)|has(name) / await text() | await jsonObject() | await jsonArray() |
// stream() -> { read(buf, off, len) } for chunk-by-chunk reads / cancel() to abort the body /
// ignoreResult() to drain it to nowhere in the background (fire-and-forget).
// text()/jsonObject()/jsonArray()/stream()/ignoreResult() consume the body once — a second throws.
//
// WIRE (v3, HOST_PROTOCOLS §1; server side: HttpProtocol.kt): a CONTROL pipe carries the JSON
// heads (self-framing — readJsonObject/JsonReader stop at the value's end) and the response body
// as raw bytes to EOF; a request body rides its OWN pipe — a one-shot command named in the head
// ("bodyCmd") that the server opens lazily on the transport's first pull, raw bytes, END = done.
// Headers cross as [[name, value], …], so head injection is impossible by construction. The
// control pipe's outbound side carries NOTHING after the head except a possible [int32 -1] —
// cancellation is observable at every stage, even a transport parked with the body never pulled.
// Authored here (with comments), minified into a Kotlin const at build time (see generateHttpJs);
// this file is never shipped as source.

(function () {
  // Byte length of str as UTF-8, WITHOUT encoding it — mirrors ymix.utf8Encode's width logic but
  // allocates nothing, so setting Content-Length on a large string body costs no copy.
  function utf8ByteLength(str) {
    var n = 0;
    for (var i = 0; i < str.length; i++) {
      var cp = str.codePointAt(i);
      if (cp > 0xffff) i++;
      n += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
    }
    return n;
  }

  // The JSON+length framing cannot be desynced by header content — these checks protect the REAL
  // HTTP client behind the host: reject early with a clear error instead of deep in the transport.
  function checkHeaderName(name) {
    name = String(name);
    if (!/^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(name))
      throw new TypeError('invalid header name: "' + name + '"');
    return name;
  }
  function checkHeaderValue(value) {
    value = String(value);
    if (/[\x00\r\n]/.test(value)) throw new TypeError("header value must not contain CR/LF/NUL");
    return value;
  }
  function checkMethod(m) {
    m = String(m);
    if (!/^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(m))
      throw new TypeError('invalid method: "' + m + '"');
    return m;
  }
  function checkUrl(url) {
    url = String(url);
    if (/[\r\n]/.test(url)) throw new TypeError("URL must not contain CR/LF");
    return url;
  }

  // Coerce untrusted (e.g. scraped) data into a legal header instead of throwing: the name keeps
  // only token characters, the value folds CR/LF runs (with surrounding blanks) into one space,
  // drops NUL and trims edge whitespace.
  function sanitizeHeaderName(name) {
    name = String(name).replace(/[^!#$%&'*+\-.^_`|~0-9A-Za-z]+/g, "");
    if (name.length === 0) throw new TypeError("header name has no valid characters");
    return name;
  }
  function sanitizeHeaderValue(value) {
    return String(value)
      .replace(/\x00/g, "")
      .replace(/[ \t]*[\r\n]+[ \t]*/g, " ")
      .trim();
  }

  function normalizeHeaders(h) {
    if (!h) return [];
    if (Array.isArray(h)) return h.slice(); // copy: we don't mutate the caller's
    return Object.keys(h).map(function (k) {
      return [k, String(h[k])];
    });
  }

  // Case-insensitive presence check over an ordered [[name, value], …] list.
  function hasHeader(entries, lowerName) {
    for (var i = 0; i < entries.length; i++)
      if (String(entries[i][0]).toLowerCase() === lowerName) return true;
    return false;
  }

  function buildHeaders(entries) {
    var map = Object.create(null); // lower-cased name -> joined value
    for (var i = 0; i < entries.length; i++) {
      var key = String(entries[i][0]).toLowerCase(),
        value = entries[i][1];
      map[key] = map[key] === undefined ? value : map[key] + ", " + value;
    }
    return {
      entries: entries,
      get: function (name) {
        var v = map[String(name).toLowerCase()];
        return v === undefined ? null : v;
      },
      has: function (name) {
        return map[String(name).toLowerCase()] !== undefined;
      },
    };
  }

  // Detach a fire-and-forget promise: its failure is deliberate to ignore (documented per call).
  function swallow(p) {
    if (p && p.catch) p.catch(function () {});
  }

  // Adopt the host's classification. HttpProtocol RESETs a transport/protocol failure with a
  // structured {code, message} reason — it classifies at the ORIGIN, where it holds the real
  // IOException (HOST_PROTOCOLS "Errors across the boundary") — so we do NOT guess a category from
  // the message text. Parse that reason onto the error; a non-structured reason (the runtime's raw
  // backstop text, or a scraper's own throw) is left untouched for commandHandler's classifier.
  // Never overwrites an existing code. Mutates the error to keep its stack.
  function hostError(e) {
    if (e && e.code === undefined && typeof e.message === "string") {
      var o = null;
      try {
        o = JSON.parse(e.message);
      } catch (_) {}
      if (o && typeof o === "object" && typeof o.code === "string") {
        e.code = o.code;
        if (typeof o.name === "string") e.name = o.name;
        if (typeof o.message === "string") e.message = o.message;
        if (typeof o.userMessage === "string") e.userMessage = o.userMessage;
      }
    }
    return e;
  }

  // A pull reader over the response body.
  class BodyStream {
    constructor(res) {
      this._res = res;
    }
    // Read up to len bytes into buffer at offset; resolves with the count, or -1 at EOF.
    read(buffer, offset, len) {
      return this._res._read(buffer, offset, len);
    }
  }

  class Response {
    constructor(status, statusText, url, headers, io, onFinished) {
      this.status = status;
      this.statusText = statusText;
      this.url = url; // final URL, after redirects
      this.ok = status >= 200 && status < 300;
      this.headers = buildHeaders(headers);
      this._io = io;
      this._onFinished = onFinished; // retires the call's body registration/pipe
      this._consumed = false;
      this._eof = false;
    }
    _take() {
      if (this._consumed) throw new Error("Response body has already been consumed");
      this._consumed = true;
    }
    // The exchange is over: half-close our write side (the cancel channel) so the server's
    // watcher sees EOF and the pipe reaps, and retire the call's body plumbing.
    _end() {
      if (this._eof) return;
      this._eof = true;
      swallow(this._io.sink.close());
      this._onFinished();
    }
    // A failed body read (server RESET — e.g. "response body failed: …" when the stream died
    // after the head): the stream is unusable past this point, so retire everything and drop our
    // read end before rethrowing.
    _fail() {
      this._end();
      this._io.source.close();
    }
    // Raw read — the body is the rest of the control pipe's inbound stream, EOF-delimited.
    async _read(buffer, offset, len) {
      if (this._eof) return -1;
      var n;
      try {
        n = await this._io.source.receive(buffer, offset, len);
      } catch (e) {
        this._fail();
        throw hostError(e);
      }
      if (n < 0) this._end();
      return n;
    }
    // Whole body in one native pass — correct across chunk boundaries (no per-chunk decode).
    async text() {
      this._take();
      var s;
      try {
        s = await this._io.source.readUtf8();
      } catch (e) {
        this._fail();
        throw hostError(e);
      }
      this._end();
      return s;
    }
    // Parse the body with the STREAMING JSON parser, validating the root type: jsonObject() requires
    // a `{…}` body, jsonArray() a `[…]` (readJsonObject/readJsonArray reject a mismatched root on the
    // first byte). Object vs array is the caller's to pick, not guessed.
    jsonObject() {
      return this._readJson((src) => src.readJsonObject());
    }
    jsonArray() {
      return this._readJson((src) => src.readJsonArray());
    }
    // Chunk-by-chunk, never materializing the whole body as a string (that's what the streaming
    // parser is for). A read failure -> network; malformed JSON -> the parser's own error (classified
    // "parse"). The parser stops at the value's end, so drain to EOF to reap the read side like
    // text() — but the body must be ONE JSON value: any non-whitespace tail is a corrupt/misframed
    // body (SyntaxError -> "parse", naming the offending byte), not silently eaten.
    async _readJson(read) {
      this._take();
      var v;
      try {
        v = await read(this._io.source);
        var scratch = new Uint8Array(64);
        for (;;) {
          var n = await this._io.source.receive(scratch, 0, scratch.length);
          if (n < 0) break; // EOF
          for (var i = 0; i < n; i++) {
            var b = scratch[i];
            if (b !== 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0d) {
              var hex = "0x" + b.toString(16).padStart(2, "0");
              var shown = b >= 0x20 && b < 0x7f ? " '" + String.fromCharCode(b) + "'" : "";
              throw new SyntaxError("unexpected data after JSON body: " + hex + shown);
            }
          }
        }
      } catch (e) {
        this._fail();
        throw hostError(e);
      }
      this._end();
      return v;
    }
    // Raw byte reads as they arrive; never buffers the whole body.
    stream() {
      this._take();
      return new BodyStream(this);
    }
    // Abort the body: -1 down the still-open write side cancels the exchange server-side even if
    // it is parked inside the transport; closeRead drops buffered bytes and EPIPEs any in-flight
    // server write. Safe at any point, even mid-stream() or after consumption.
    cancel() {
      this._consumed = true;
      var io = this._io;
      if (!this._eof) {
        this._eof = true;
        swallow(io.sink.writeInt(-1));
        swallow(io.sink.close());
        this._onFinished();
      }
      io.source.close();
    }
    // Fire-and-forget, like cancel() but polite: mark the body ignored and drain it to nowhere in
    // the background so the exchange completes with a clean END. Nothing to await; a mid-drain
    // failure just ends the drain (the caller declared they don't care), so it can never surface
    // an unhandled rejection.
    ignoreResult() {
      this._take();
      var self = this;
      (async function () {
        var buf = new Uint8Array(8192);
        try {
          for (;;) {
            if ((await self._read(buf, 0, buf.length)) < 0) return;
          }
        } catch (e) {
          self._fail();
        }
      })();
    }
  }

  // One request execution. execute() resolves the Response (once); cancel() aborts at ANY stage:
  // before execute() (it refuses to start), mid-flight (a RESET of the control pipe's request
  // direction — the server watcher treats it like the in-band -1 and aborts the exchange, even
  // one parked inside its transport with the body never pulled; execute() rejects
  // "request cancelled"), or after the Response (delegates to Response.cancel()).
  class HttpCall {
    constructor(method, url, headers, kind, body, len, opts, logCtx) {
      this._method = method;
      this._url = url;
      this._headers = headers;
      this._kind = kind;
      this._body = body;
      this._len = len;
      this._opts = opts; // null, or {timeoutMs?, followRedirects?}
      this._logCtx = logCtx; // null, or the caller's logging-context trail for birth-site logs
      this._io = null;
      this._response = null;
      this._bodyReg = null; // the body pipe's one-shot accept registration, until opened
      this._bodyPipe = null; // the body pipe, while the host is pulling from it
      this._started = false;
      this._cancelled = false;
    }
    // Retire the body plumbing: unregister a never-opened body command, abort an in-flight body
    // transfer. Runs on every terminal path (response consumed, cancel, failure) — a request
    // whose body the server never pulled (cache hit, early error) must not leak the registration.
    _retireBody(reason) {
      var reg = this._bodyReg;
      if (reg) {
        this._bodyReg = null;
        reg.remove();
      }
      var bp = this._bodyPipe;
      if (bp) {
        this._bodyPipe = null;
        bp.sink.close(reason); // RESET: allowed mid-write; a no-op if the body already ENDed cleanly
        bp.source.close();
      }
    }
    cancel() {
      if (this._cancelled) return;
      this._cancelled = true;
      if (this._response) return this._response.cancel(); // its _onFinished retires the body
      this._retireBody("cancelled");
      var io = this._io;
      if (io) {
        io.sink.close("cancelled"); // RESET: allowed mid-write, rejects a parked send
        io.source.close();
      }
    }
    async execute() {
      if (this._started) throw new Error("execute() called twice");
      this._started = true;
      if (this._cancelled) throw new Error("request cancelled");
      var self = this;
      var io = ymix.open("http");
      this._io = io;
      // The body rides its own pipe: register a one-shot command the server opens lazily, on the
      // transport's FIRST body pull — a response that never needs the body never transfers it.
      // Raw bytes, END = body complete; the callback kind gets the pipe's real streaming sink.
      var head = { method: this._method, url: this._url, headers: this._headers };
      if (this._kind !== null) {
        head.bodyCmd = "http/body/" + io.id;
        this._bodyReg = ymix.onAccept(head.bodyCmd, async function (bp) {
          self._bodyReg = null;
          self._bodyPipe = bp;
          var p;
          if (self._kind === "text") {
            p = bp.sink.writeUtf8(self._body);
            if (p) await p;
          } else if (self._kind === "bytes") {
            p = bp.sink.writeBytes(self._body);
            if (p) await p;
          } else {
            await self._body(bp.sink); // capability: a sink the callback cannot read or reopen
          }
          await bp.sink.close(); // END: body complete
          self._bodyPipe = null;
        });
      }
      if (this._opts) head.opts = this._opts;
      // Threads the caller's logging context to the host so a transport failure (request/response
      // body) logs at its birth site with the full trail (HOST_PROTOCOLS "Logging context").
      if (this._logCtx) head.logCtx = this._logCtx;
      try {
        // control pipe: the JSON head (self-framing), then NOTHING outbound except a possible -1
        // cancel — the channel stays unobstructed at every stage.
        var p = io.sink.writeObjectAsJson(head);
        if (p) await p;
        p = io.sink.flush();
        if (p) await p;

        // response: JSON head, then the raw body bytes to EOF on the same pipe.
        var rh = await io.source.readJsonObject();
        var res = new Response(
          rh.status | 0,
          rh.message === undefined ? "" : String(rh.message),
          rh.url === undefined ? this._url : String(rh.url),
          rh.headers || [],
          io,
          function () {
            self._retireBody("request finished");
          },
        );
        this._response = res;
        return res;
      } catch (e) {
        // No Response will be returned, so nothing else will ever own the control pipe — retire it
        // here (like OkHttp's Call.execute() releasing its resources on a failed call). Idempotent
        // with a later cancel() (pipe close is first-close-wins).
        this._retireBody("request failed");
        var io2 = this._io;
        if (io2) {
          this._io = null;
          io2.sink.close();
          io2.source.close();
        }
        if (this._cancelled) throw new Error("request cancelled");
        throw hostError(e);
      }
    }
  }

  class HttpRequestBuilder {
    constructor(method, url) {
      this._method = checkMethod(method);
      this._url = checkUrl(url);
      this._headers = []; // ordered multimap: [[name, value], …] (addHeader can repeat a name)
      this._body = null;
    }
    // Set: replace any existing same-name header (case-insensitive), then append.
    setHeader(name, value) {
      name = checkHeaderName(name);
      var lower = name.toLowerCase();
      this._headers = this._headers.filter(function (e) {
        return String(e[0]).toLowerCase() !== lower;
      });
      this._headers.push([name, checkHeaderValue(value)]);
      return this;
    }
    // Append: keep any existing same-name header (for genuine multi-value headers).
    addHeader(name, value) {
      this._headers.push([checkHeaderName(name), checkHeaderValue(value)]);
      return this;
    }
    // setHeader()/addHeader() for untrusted (scraped) data: coerce into a legal header, never
    // throw (except a name with no valid characters at all).
    setHeaderSanitized(name, value) {
      return this.setHeader(sanitizeHeaderName(name), sanitizeHeaderValue(value));
    }
    addHeaderSanitized(name, value) {
      return this.addHeader(sanitizeHeaderName(name), sanitizeHeaderValue(value));
    }
    // Bulk set from an object or an [[name, value], …] list (later same-name entries win).
    headers(h) {
      var e = normalizeHeaders(h);
      for (var i = 0; i < e.length; i++) this.setHeader(e[i][0], e[i][1]);
      return this;
    }
    // Body: a string, a Uint8Array, or an (io) => … callback that writes to the buffered sink.
    body(value) {
      this._body = value;
      return this;
    }
    // Sugar: write value as a JSON body, defaulting Content-Type when the caller hasn't set one.
    json(value) {
      if (!hasHeader(this._headers, "content-type"))
        this.setHeader("Content-Type", "application/json");
      this._body = function (io) {
        return io.writeObjectAsJson(value);
      }; // returned promise is awaited by execute()
      return this;
    }
    // Per-request deadline for the whole exchange, in milliseconds (server default otherwise).
    timeout(ms) {
      if (!Number.isFinite(ms) || ms <= 0)
        throw new RangeError("timeout must be a positive number of ms");
      this._timeoutMs = Math.floor(ms);
      return this;
    }
    // Follow (or not) redirects for this request (server default otherwise).
    followRedirects(follow) {
      this._followRedirects = !!follow;
      return this;
    }
    // Logging context: a logical scope trail (["downloadController","js:resolve",…]) the host tags a
    // birth-site failure (request/response body) with. Developer-only (scope labels, never data/PII)
    // — it never reaches the user; the error code does. Rides the head to HttpProtocol.
    logContext(ctx) {
      this._logCtx = Array.isArray(ctx) ? ctx.slice() : [String(ctx)];
      return this;
    }

    // Snapshot this builder into an executable, cancellable call.
    build() {
      var headers = this._headers.slice(),
        body = this._body,
        kind = null,
        len = -1;
      if (body != null) {
        if (typeof body === "string") {
          kind = "text";
          len = utf8ByteLength(body);
        } else if (body instanceof Uint8Array) {
          kind = "bytes";
          len = body.length;
        } else if (typeof body === "function") {
          kind = "callback";
        } // length unknown (each write crosses as its own chunk)
        else throw new TypeError("body must be a string, Uint8Array, or (io) => … callback");
        if (len >= 0 && !hasHeader(headers, "content-length"))
          headers.push(["Content-Length", String(len)]);
      } else if (this._method === "POST" || this._method === "PUT" || this._method === "PATCH") {
        // no silent empty-body substitution — an intentionally empty body is .body("")
        throw new TypeError(this._method + ' requires a body — use .body("") to send an empty one');
      }
      var opts = null;
      if (this._timeoutMs !== undefined) (opts = opts || {}).timeoutMs = this._timeoutMs;
      if (this._followRedirects !== undefined)
        (opts = opts || {}).followRedirects = this._followRedirects;
      return new HttpCall(
        this._method,
        this._url,
        headers,
        kind,
        body,
        len,
        opts,
        this._logCtx || null,
      );
    }
  }

  function verb(method) {
    return function (url) {
      return new HttpRequestBuilder(method, url);
    };
  }
  globalThis.HttpRequest = {
    get: verb("GET"),
    post: verb("POST"),
    put: verb("PUT"),
    delete: verb("DELETE"),
    patch: verb("PATCH"),
    head: verb("HEAD"),
    method: function (m, url) {
      return new HttpRequestBuilder(String(m).toUpperCase(), url);
    },
  };
})();
