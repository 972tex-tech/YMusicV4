// serve() — the call-protocol dispatcher and the JS half of the error contract (HOST_PROTOCOLS
// "Errors across the boundary"). serve(command, handler) reads the JSON args head, runs
// handler(args, out) with the pipe's buffered sink, ENDs on success; ANY throw is classified into
// {code, name, message, stack, userMessage} and RESETs the pipe with that object as JSON — the
// host rebuilds it as a typed EngineError, so a failure keeps its meaning across the boundary.
//
// A code is either a well-known category the app localizes ("network", "parse", "oom") or a site
// code E##### — a unique hardcoded literal at one throw site, answered by a global search
// (E2xxxx = :dynamic-engine, including this remote JS). Codes pass through tiers unchanged: wrap,
// never re-mint. ExtractError is the deliberate-throw carrier:
//   throw ExtractError.parse("continuation token missing");
//   throw new ExtractError("E29999", "shelf renderer shape", { userMessage: "…" });
// Authored here (with comments), minified into a Kotlin const at build time (see the preamble
// task in build.gradle.kts); this file is never shipped as source.

(function () {
  var STACK_CAP = 4096;

  class ExtractError extends Error {
    // code: a category ("parse", "network", …) or a site code ("E2xxxx");
    // opts.userMessage: localized, display-ready text; opts.cause: the underlying error.
    constructor(code, message, opts) {
      super(message, opts);
      this.name = "ExtractError";
      this.code = String(code);
      if (opts && opts.userMessage !== undefined) this.userMessage = String(opts.userMessage);
    }
    static parse(message, opts) {
      return new ExtractError("parse", message, opts);
    }
    static network(message, opts) {
      return new ExtractError("network", message, opts);
    }
  }
  globalThis.ExtractError = ExtractError;

  // Classify a thrown value into the wire error object. An explicit .code wins (ours — including
  // http.js's "network" tagging); a site code embedded in the message is preserved (host reasons
  // cross as text, e.g. "E20011 invalid request: …"); engine InternalError = OOM/stack overflow;
  // a runtime SyntaxError is almost always JSON.parse on scraped data; anything else is an
  // uncaught script error — E20001.
  function classify(e) {
    var msg = e && e.message !== undefined ? String(e.message) : String(e);
    var code =
      e && typeof e.code === "string"
        ? e.code
        : (msg.match(/\bE\d{5}\b/) || [])[0] ||
          (e && e.name === "InternalError" ? "oom" : e instanceof SyntaxError ? "parse" : "E20001"); // E20001: uncaught script error
    var out = { code: code, message: msg };
    if (e && e.name) out.name = String(e.name);
    if (e && typeof e.userMessage === "string") out.userMessage = e.userMessage;
    if (e && typeof e.stack === "string") out.stack = e.stack.slice(0, STACK_CAP);
    var c = e && e.cause;
    if (c)
      out.cause = {
        name: c.name === undefined ? undefined : String(c.name),
        message: c.message === undefined ? String(c) : String(c.message),
      };
    return out;
  }

  globalThis.serve = function (command, handler) {
    return ymix.onAccept(command, async function (p) {
      try {
        var args = await p.source.readJsonObject();
        await handler(args, p.sink);
        var pr = p.sink.close(); // END: call complete
        if (pr) await pr;
      } catch (e) {
        if (!/^EPIPE\b/.test((e && e.message) || "")) {
          // EPIPE = the caller hung up: no one left to report to. Everything else crosses as a
          // structured RESET; a failure of the close itself has nowhere to go — swallow it.
          try {
            var pr2 = p.sink.close(JSON.stringify(classify(e)));
            if (pr2) await pr2;
          } catch (ignored) {}
        }
      } finally {
        p.source.close();
      }
    });
  };
})();
