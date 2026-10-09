// commandHandler() — the JS half of the error contract (HOST_PROTOCOLS "Errors across the
// boundary"). commandHandler(handler) wraps a call handler into a pipe handler for ymix.onAccept:
//   ymix.onAccept("resolve_download", commandHandler(async (src, out) => { … }))
// The handler runs as handler(src, out[, pipe]): it reads its own head off `src` (JSON via
// src.readJsonObject()/readJsonArray(), a line, length-prefixed, or nothing — the args format is NOT
// assumed) and writes the result to `out`. ENDs on success; ANY throw is classified into
// {code, name, message, stack, userMessage} and RESETs the pipe with that object as JSON — the host
// rebuilds it as a typed EngineError, so a failure keeps its meaning across tiers.
//
// A code is either a well-known category the app localizes ("network", "parse", "oom") or a site
// code E##### — a unique hardcoded literal at one throw site, answered by a global search
// (E2xxxx = :dynamic-engine, including this remote JS). Codes pass through tiers unchanged: wrap,
// never re-mint. JsEngineError is the deliberate-throw carrier (message first — the debug detail you
// read most — then the code):
//   throw JsEngineError.parse("continuation token missing");
//   throw new JsEngineError("shelf renderer shape", "E29999", { userMessage: "…" });
// Types: the ambient contract in src/types.d.ts (JsEngineError, WireError, commandHandler) — this
// file is checked against it. Bundled+minified into the :engine-js single bundle (src/index.ts);
// never shipped as source.

import { open } from "../transport";

const STACK_CAP = 4096;

export class JsEngineErrorImpl extends Error {
  code: string;
  userMessage?: string;

  // message: technical detail for logs/grep; code: a category ("parse", "network", …) or a site
  // code ("E2xxxx"); opts.userMessage: localized display text; opts.cause: the underlying error.
  constructor(message: string, code: string, opts?: JsEngineErrorOptions) {
    super(message, opts as ErrorOptions | undefined);
    this.name = "JsEngineError";
    this.code = String(code);
    if (opts && opts.userMessage !== undefined) this.userMessage = String(opts.userMessage);
  }
  static parse(message: string, opts?: JsEngineErrorOptions): JsEngineErrorImpl {
    return new JsEngineErrorImpl(message, "parse", opts);
  }
  static network(message: string, opts?: JsEngineErrorOptions): JsEngineErrorImpl {
    return new JsEngineErrorImpl(message, "network", opts);
  }
}
globalThis.JsEngineError = JsEngineErrorImpl;

// A thrown value as classify sees it: anything, probed field by field.
interface ThrownLike {
  message?: unknown;
  code?: unknown;
  name?: unknown;
  userMessage?: unknown;
  stack?: unknown;
  cause?: { name?: unknown; message?: unknown };
}

// Classify a thrown value into the wire error object — the WireError contract (Kotlin mirror:
// WireError.kt, decoded by decodeEngineError). An explicit .code wins (ours — including http.ts's
// host-adopted codes); a site code embedded in the message is preserved (host reasons can cross as
// text); engine InternalError = OOM/stack overflow; a runtime SyntaxError is almost always
// JSON.parse on scraped data; anything else is an uncaught script error — E20001.
// Wire keys are QUOTED (Closure never renames quoted properties): this object crosses to Kotlin as
// JSON, so its keys must survive the ADVANCED pass literally. The error-object reads (t.code, …)
// stay dotted — those are in-bundle properties that rename consistently.
function classify(e: unknown): WireError {
  const t = (e ?? {}) as ThrownLike;
  const msg = t.message !== undefined ? String(t.message) : String(e);
  const code =
    typeof t.code === "string"
      ? t.code
      : (msg.match(/\bE\d{5}\b/) || [])[0] ||
        (t.name === "InternalError" ? "oom" : e instanceof SyntaxError ? "parse" : "E20001"); // E20001: uncaught script error
  const out: WireError = { "code": code, "message": msg };
  if (t.name) out["name"] = String(t.name);
  if (typeof t.userMessage === "string") out["userMessage"] = t.userMessage;
  if (typeof t.stack === "string") out["stack"] = t.stack.slice(0, STACK_CAP);
  const c = t.cause;
  if (c) {
    const cause: WireError["cause"] = {
      "message": c.message === undefined ? String(c) : String(c.message),
    };
    if (c.name !== undefined) cause["name"] = String(c.name);
    out["cause"] = cause;
  }
  return out;
}

// Wrap a call handler in the error contract, producing a pipe handler to hand to ymix.onAccept.
// It runs handler(source, sink, pipe): the handler reads its own head off `source` (format not
// assumed) and writes the result to `sink`, then END on success; ANY throw is classified and RESETs
// the pipe with it. Always closes the source; EPIPE (the caller hung up) is the one silent path.
export function commandHandler(handler: CommandHandlerFn): (pipe: Pipe) => Promise<void> {
  return async function (p: Pipe): Promise<void> {
    try {
      await handler(p.source, p.sink, p);
      const pr = p.sink.close(); // END: call complete
      if (pr) await pr;
    } catch (e) {
      if (!/^EPIPE\b/.test(String((e as ThrownLike | null)?.message ?? ""))) {
        // EPIPE = the caller hung up: no one left to report to. Everything else crosses as a
        // structured RESET; a failure of the close itself has nowhere to go — swallow it.
        try {
          const pr2 = p.sink.close(JSON.stringify(classify(e)));
          if (pr2) await pr2;
        } catch (ignored) {}
      }
    } finally {
      p.source.close();
    }
  };
}
// Global mirror for externally-evaled JS (dev/debug); in-bundle code imports. Mangles in Closure.
globalThis.commandHandler = commandHandler;

// ---- the caller half of the contract: open a host command, decode its RESET ----

// The mutable view of a thrown error hostError writes the adopted classification onto.
interface TaggableError {
  code?: string;
  message?: string;
  name?: string;
  userMessage?: string;
}

// Adopt the host's classification — the decode twin of classify(), and the JS mirror of Kotlin's
// decodeEngineError. A host (HttpProtocol, commandHandler) RESETs a failure with a structured
// {code, message, …} reason, classified at the ORIGIN where the real exception is understood
// (HOST_PROTOCOLS "Errors across the boundary") — so we do NOT guess a category from the text. Parse
// that reason onto the error; a non-structured reason (the transport's raw backstop text, or a
// scraper's own throw) is left untouched for commandHandler's classifier. Never overwrites an
// existing code. Mutates the error to keep its stack.
// Wire reads are BRACKETED (w["code"], …): `o` is parsed wire JSON, whose keys are literal — the
// dotted writes onto `t` are in-bundle error properties that rename consistently under Closure.
export function hostError(e: unknown): unknown {
  const t = e as TaggableError | null;
  if (t && t.code === undefined && typeof t.message === "string") {
    let o: unknown = null;
    try {
      o = JSON.parse(t.message);
    } catch (_) {}
    if (o && typeof o === "object" && typeof (o as WireError)["code"] === "string") {
      const w = o as WireError;
      t.code = w["code"];
      if (typeof w["name"] === "string") t.name = w["name"];
      if (typeof w["message"] === "string") t.message = w["message"];
      if (typeof w["userMessage"] === "string") t.userMessage = w["userMessage"];
    }
  }
  return e;
}

// callHost(command, body) — open a host-served command, run `body` over the pipe, and turn any host
// RESET into a classified error (hostError parses the {code,…} reason onto the thrown error, so the
// caller can branch on `.code`). The generic form of what http/call.ts does by hand, and the JS twin
// of Kotlin's openPipe + decodeEngineError. Always closes both ends; `body` owns the reads/writes.
export async function callHost<T>(command: string, body: (pipe: Pipe) => Promise<T>): Promise<T> {
  const pipe = open(command);
  try {
    return await body(pipe);
  } catch (e) {
    // RESET, not END: a body cut short by a throwing writer must not reach the host as complete.
    try {
      const r = pipe.sink.close("aborted: " + (e instanceof Error ? e.message : String(e)));
      if (r) await r;
    } catch (_e) {}
    throw hostError(e);
  } finally {
    const w = pipe.sink.close(); // idempotent if `body` already closed it
    if (w) await w;
    pipe.source.close();
  }
}

/** Player session → current main engine-js. `open("host/main/"+command)`; args/result on the pipe. */
export function openMain(command: string): Pipe {
  return open("host/main/" + command);
}

export async function callMain<T>(command: string, body: (pipe: Pipe) => Promise<T>): Promise<T> {
  return callHost("host/main/" + command, body);
}
