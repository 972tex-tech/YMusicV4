// WHATWG fetch over HttpRequest, for eval'd base.js. Lives next to the YouTube cookie policy so
// fetchImpl can put Cookie on the builder (CONSENT/SOCS). Quoted names so Closure ADVANCED leaves
// the browser-shaped API intact. fetch() resolves on the response head; text/json/arrayBuffer go
// through HttpResponse (_http.bytes for arrayBuffer). getReader pulls HttpResponse.stream().

import { debug } from "../../debug";
import { stringifyJson, utf8Decode, utf8Encode } from "../../transport";
import { HttpRequestBuilderImpl } from "../../protocol/http/builder";
import {
  checkHeaderName,
  checkHeaderValue,
  checkMethod,
  checkUrl,
  resolveUrl,
} from "../../protocol/http/headers";
import { attachRequestIdentity, isYoutubeiUrl, storeSetCookies } from "./cookies";
import type { AuthContext } from "./cookies";
import { getSession, SESSION_MAX_AGE_MS } from "./session";
import { allowEngineJsUrl } from "./jsAllow";

function native(name: string, impl: Function): Function {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl);
  return impl;
}

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function abortError(): Error {
  const e = new Error("The operation was aborted.");
  e.name = "AbortError";
  return e;
}

function failedToFetch(e: unknown): TypeError {
  const msg = e instanceof Error ? e.message : String(e);
  const err = new TypeError("Failed to fetch: " + msg);
  (err as Error & { cause?: unknown }).cause = e;
  return err;
}

function asBody(v: unknown): string | Uint8Array | undefined {
  if (v == null) return undefined;
  if (typeof v === "string") return v;
  if (v instanceof Uint8Array) return v;
  if (v instanceof ArrayBuffer) return new Uint8Array(v);
  if (ArrayBuffer.isView(v)) return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
  return String(v);
}

type HeaderCb = (value: string, name: string) => void;

interface HeadersJs {
  _entries: HeaderEntry[];
  get(name: string): string | null;
  has(name: string): boolean;
  set(name: string, value: string): void;
  append(name: string, value: string): void;
  delete(name: string): void;
  forEach(cb: HeaderCb): void;
}

function HeadersImpl(this: HeadersJs, init?: unknown) {
  this._entries = [];
  if (init != null) applyHeaders(this, init);
}

function applyHeaders(h: HeadersJs, init: unknown): void {
  if (init == null) return;
  if (Array.isArray(init)) {
    for (let i = 0; i < init.length; i++) {
      const pair = init[i] as unknown;
      if (!Array.isArray(pair) || pair.length < 2) continue;
      h["append"](String(pair[0]), String(pair[1]));
    }
    return;
  }
  const rec = asRec(init);
  if (rec && typeof rec["forEach"] === "function") {
    (rec["forEach"] as (cb: HeaderCb) => void)(function (value: string, name: string) {
      h["append"](name, value);
    });
    return;
  }
  if (rec) {
    const keys = Object.keys(rec);
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i]!;
      h["append"](k, String(rec[k]));
    }
  }
}

HeadersImpl.prototype["get"] = function (this: HeadersJs, name: string): string | null {
  const lower = String(name).toLowerCase();
  let joined: string | null = null;
  for (let i = 0; i < this._entries.length; i++) {
    const e = this._entries[i]!;
    if (String(e[0]).toLowerCase() !== lower) continue;
    joined = joined == null ? e[1] : joined + ", " + e[1];
  }
  return joined;
};

HeadersImpl.prototype["has"] = function (this: HeadersJs, name: string): boolean {
  return this["get"](name) != null;
};

HeadersImpl.prototype["set"] = function (this: HeadersJs, name: string, value: string): void {
  name = checkHeaderName(name);
  value = checkHeaderValue(value);
  const lower = name.toLowerCase();
  this._entries = this._entries.filter(function (e) {
    return String(e[0]).toLowerCase() !== lower;
  });
  this._entries.push([name, value]);
};

HeadersImpl.prototype["append"] = function (this: HeadersJs, name: string, value: string): void {
  this._entries.push([checkHeaderName(name), checkHeaderValue(value)]);
};

HeadersImpl.prototype["delete"] = function (this: HeadersJs, name: string): void {
  const lower = String(name).toLowerCase();
  this._entries = this._entries.filter(function (e) {
    return String(e[0]).toLowerCase() !== lower;
  });
};

HeadersImpl.prototype["forEach"] = function (this: HeadersJs, cb: HeaderCb): void {
  for (let i = 0; i < this._entries.length; i++) {
    const e = this._entries[i]!;
    cb(e[1], String(e[0]).toLowerCase());
  }
};

function makeHeaders(init?: unknown): HeadersJs {
  const h = Object.create(HeadersImpl.prototype) as HeadersJs;
  HeadersImpl.call(h, init);
  return h;
}

function cloneHeaders(src: HeadersJs): HeadersJs {
  const h = makeHeaders();
  h._entries = src._entries.map(function (e): HeaderEntry {
    return [e[0], e[1]];
  });
  return h;
}

interface AbortSignalJs {
  aborted: boolean;
  onabort: (() => void) | null;
  _listeners: Array<() => void>;
  addEventListener(type: string, fn: () => void): void;
  removeEventListener(type: string, fn: () => void): void;
}

function AbortSignalImpl(this: AbortSignalJs) {
  this["aborted"] = false;
  this["onabort"] = null;
  this._listeners = [];
}

AbortSignalImpl.prototype["addEventListener"] = function (
  this: AbortSignalJs,
  type: string,
  fn: () => void,
): void {
  if (type !== "abort" || typeof fn !== "function") return;
  this._listeners.push(fn);
};

AbortSignalImpl.prototype["removeEventListener"] = function (
  this: AbortSignalJs,
  type: string,
  fn: () => void,
): void {
  const i = this._listeners.indexOf(fn);
  if (i >= 0) this._listeners.splice(i, 1);
};

interface AbortControllerJs {
  signal: AbortSignalJs;
  abort(): void;
}

function AbortControllerImpl(this: AbortControllerJs) {
  const s = Object.create(AbortSignalImpl.prototype) as AbortSignalJs;
  AbortSignalImpl.call(s);
  this["signal"] = s;
}

AbortControllerImpl.prototype["abort"] = function (this: AbortControllerJs): void {
  const s = this["signal"];
  if (s["aborted"]) return;
  s["aborted"] = true;
  const list = s._listeners.slice();
  for (let i = 0; i < list.length; i++) list[i]!();
  const on = s["onabort"];
  if (typeof on === "function") on();
};

type PullChunk = () => Promise<Uint8Array | null>;

interface StreamJs {
  locked: boolean;
  _locked: boolean;
  _cancelled: boolean;
  _pull: PullChunk;
  _cancelSrc: (() => void) | null;
  getReader(): ReaderJs;
  cancel(): Promise<undefined>;
}

interface ReaderJs {
  _stream: StreamJs;
  read(): Promise<Record<string, unknown>>;
  cancel(): Promise<undefined>;
  releaseLock(): void;
}

function concatBytes(parts: Uint8Array[], total: number): Uint8Array {
  const out = new Uint8Array(total);
  let off = 0;
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    out.set(p, off);
    off += p.length;
  }
  return out;
}

function memoryPull(bytes: Uint8Array): PullChunk {
  let done = false;
  return function () {
    if (done) return Promise.resolve(null);
    done = true;
    return Promise.resolve(bytes.length === 0 ? null : bytes);
  };
}

/** Stringify only if someone reads the body stream (json()/text() lock first). */
function jsonPull(rec: unknown): PullChunk {
  let done = false;
  return function () {
    if (done) return Promise.resolve(null);
    done = true;
    const bytes = stringifyJson(rec);
    return Promise.resolve(bytes.length === 0 ? null : bytes);
  };
}

function httpPull(httpRes: HttpResponse, onDone: () => void): PullChunk {
  const buf = new Uint8Array(8192);
  let stream: HttpBodyStream | null = null;
  let ended = false;
  return async function () {
    if (ended) return null;
    if (!stream) stream = httpRes.stream();
    let n: number;
    try {
      n = await stream.read(buf, 0, buf.length);
    } catch (e) {
      ended = true;
      onDone();
      throw e;
    }
    if (n < 0) {
      ended = true;
      onDone();
      return null;
    }
    return buf.slice(0, n);
  };
}

function teePull(src: PullChunk): [PullChunk, PullChunk] {
  const q0: Array<Uint8Array | null> = [];
  const q1: Array<Uint8Array | null> = [];
  let pending: Promise<void> | null = null;
  let err: unknown = null;
  function pump(): Promise<void> {
    if (err) return Promise.reject(err);
    if (pending) return pending;
    pending = src()
      .then(function (chunk) {
        q0.push(chunk);
        q1.push(chunk == null ? null : chunk.slice());
      })
      .then(
        function () {
          pending = null;
        },
        function (e) {
          pending = null;
          err = e;
          throw e;
        },
      );
    return pending;
  }
  function branch(q: Array<Uint8Array | null>): PullChunk {
    return async function () {
      if (err) throw err;
      while (q.length === 0) {
        await pump();
        if (err) throw err;
      }
      return q.shift()!;
    };
  }
  return [branch(q0), branch(q1)];
}

function ReadableStreamImpl(this: StreamJs, bytes?: Uint8Array) {
  this._locked = false;
  this._cancelled = false;
  this._pull = memoryPull(bytes instanceof Uint8Array ? bytes : new Uint8Array(0));
  this._cancelSrc = null;
}

function makeStream(pull: PullChunk, cancelSrc?: (() => void) | null): StreamJs {
  const s = Object.create(ReadableStreamImpl.prototype) as StreamJs;
  s._locked = false;
  s._cancelled = false;
  s._pull = pull;
  s._cancelSrc = cancelSrc || null;
  return s;
}

Object.defineProperty(ReadableStreamImpl.prototype, "locked", {
  get: function (this: StreamJs) {
    return this._locked;
  },
});

ReadableStreamImpl.prototype["getReader"] = function (this: StreamJs): ReaderJs {
  if (this._locked) throw new TypeError("ReadableStream is locked");
  this._locked = true;
  const r = Object.create(ReaderImpl.prototype) as ReaderJs;
  ReaderImpl.call(r, this);
  return r;
};

ReadableStreamImpl.prototype["cancel"] = function (this: StreamJs): Promise<undefined> {
  this._cancelled = true;
  this._locked = false;
  const c = this._cancelSrc;
  this._cancelSrc = null;
  if (typeof c === "function") c();
  return Promise.resolve(undefined);
};

function ReaderImpl(this: ReaderJs, stream: StreamJs) {
  this._stream = stream;
}

ReaderImpl.prototype["read"] = function (this: ReaderJs): Promise<Record<string, unknown>> {
  const s = this._stream;
  if (s._cancelled) {
    return Promise.resolve({ "value": undefined, "done": true });
  }
  return s._pull().then(
    function (chunk) {
      if (s._cancelled || chunk == null) {
        return { "value": undefined, "done": true };
      }
      return { "value": chunk, "done": false };
    },
    function (e) {
      if (s._cancelled) return { "value": undefined, "done": true };
      throw e;
    },
  );
};

ReaderImpl.prototype["cancel"] = function (this: ReaderJs): Promise<undefined> {
  return this._stream["cancel"]();
};

ReaderImpl.prototype["releaseLock"] = function (this: ReaderJs): void {
  this._stream._locked = false;
};

interface FetchResponseJs {
  status: number;
  ok: boolean;
  statusText: string;
  url: string;
  headers: HeadersJs;
  body: StreamJs;
  bodyUsed: boolean;
  redirected: boolean;
  type: string;
  _bytes: Uint8Array | null;
  _json: unknown;
  _http: HttpResponse | null;
  text(): Promise<string>;
  json(): Promise<unknown>;
  arrayBuffer(): Promise<ArrayBuffer>;
  clone(): FetchResponseJs;
}

function fillResponse(
  self: FetchResponseJs,
  status: number,
  statusText: string,
  url: string,
  headers: HeadersJs,
  stream: StreamJs,
  redirected: boolean,
): void {
  self["status"] = status;
  self["ok"] = status >= 200 && status < 300;
  self["statusText"] = statusText;
  self["url"] = url;
  self["headers"] = headers;
  self["body"] = stream;
  self["bodyUsed"] = false;
  self["redirected"] = redirected;
  self["type"] = "basic";
}

function ResponseImpl(this: FetchResponseJs, body?: unknown, init?: unknown) {
  const rec = asRec(init);
  const status = rec && typeof rec["status"] === "number" ? (rec["status"] as number) : 200;
  const statusText = rec && rec["statusText"] != null ? String(rec["statusText"]) : "";
  const headers = makeHeaders(rec ? rec["headers"] : undefined);
  const raw = asBody(body);
  const bytes =
    raw === undefined ? new Uint8Array(0) : typeof raw === "string" ? utf8Encode(raw) : raw;
  fillResponse(this, status, statusText, "", headers, makeStream(memoryPull(bytes)), false);
  this._bytes = bytes;
  this._json = null;
  this._http = null;
}

function takeBody(res: FetchResponseJs): void {
  if (res["bodyUsed"] || res["body"]._locked) throw new TypeError("body already used");
  res["bodyUsed"] = true;
  res["body"]._locked = true;
}

async function collect(res: FetchResponseJs): Promise<Uint8Array> {
  takeBody(res);
  if (res._http) return res._http.bytes();
  if (res._json != null) return stringifyJson(res._json);
  if (res._bytes != null) return res._bytes;
  const parts: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const chunk = await res["body"]._pull();
    if (chunk == null) break;
    parts.push(chunk);
    total += chunk.length;
  }
  return concatBytes(parts, total);
}

ResponseImpl.prototype["text"] = function (this: FetchResponseJs): Promise<string> {
  if (this._http) {
    takeBody(this);
    return this._http.text();
  }
  if (this._json != null) {
    takeBody(this);
    return Promise.resolve(JSON.stringify(this._json) || "");
  }
  return collect(this).then(function (u8) {
    return utf8Decode(u8);
  });
};

ResponseImpl.prototype["json"] = function (this: FetchResponseJs): Promise<unknown> {
  if (this._http) {
    takeBody(this);
    return this._http.text().then(function (t) {
      return JSON.parse(t as string);
    });
  }
  if (this._json != null) {
    takeBody(this);
    return Promise.resolve(this._json);
  }
  return collect(this).then(function (u8) {
    return __parseJson(u8);
  });
};

ResponseImpl.prototype["arrayBuffer"] = function (this: FetchResponseJs): Promise<ArrayBuffer> {
  return collect(this).then(function (u8) {
    // Pipe bodies are never SharedArrayBuffer-backed.
    if (u8.byteOffset === 0 && u8.byteLength === u8.buffer.byteLength)
      return u8.buffer as ArrayBuffer;
    return u8.slice().buffer;
  });
};

ResponseImpl.prototype["clone"] = function (this: FetchResponseJs): FetchResponseJs {
  if (this["bodyUsed"] || this["body"]._locked) throw new TypeError("body already used");
  const copy = Object.create(ResponseImpl.prototype) as FetchResponseJs;
  // An HttpResponse body is read-once: both copies read the tee'd stream below instead.
  this._http = null;
  if (this._json != null) {
    fillResponse(
      copy,
      this["status"],
      this["statusText"],
      this["url"],
      cloneHeaders(this["headers"]),
      makeStream(jsonPull(this._json)),
      this["redirected"],
    );
    copy._bytes = null;
    copy._json = this._json;
    copy._http = null;
    return copy;
  }
  if (this._bytes != null) {
    const bytes = this._bytes.slice();
    fillResponse(
      copy,
      this["status"],
      this["statusText"],
      this["url"],
      cloneHeaders(this["headers"]),
      makeStream(memoryPull(bytes)),
      this["redirected"],
    );
    copy._bytes = bytes;
    copy._json = null;
    copy._http = null;
    return copy;
  }
  const body = this["body"];
  const srcCancel = body._cancelSrc;
  body._cancelSrc = null;
  const pulls = teePull(body._pull);
  body._pull = pulls[0];
  let left = 2;
  const branchCancel = function () {
    left--;
    if (left === 0 && typeof srcCancel === "function") srcCancel();
  };
  body._cancelSrc = branchCancel;
  fillResponse(
    copy,
    this["status"],
    this["statusText"],
    this["url"],
    cloneHeaders(this["headers"]),
    makeStream(pulls[1], branchCancel),
    this["redirected"],
  );
  copy._bytes = null;
  copy._json = null;
  copy._http = null;
  return copy;
};

interface RequestJs {
  url: string;
  method: string;
  headers: HeadersJs;
  body: string | Uint8Array | undefined;
  signal: AbortSignalJs | null;
}

interface Parsed {
  url: string;
  method: string;
  headers: HeadersJs;
  body: string | Uint8Array | undefined;
  signal: AbortSignalJs | null;
  redirect: string;
}

function parseInput(input: unknown, init?: unknown): Parsed {
  let url = "";
  let method = "GET";
  let headers = makeHeaders();
  let body: string | Uint8Array | undefined;
  let signal: AbortSignalJs | null = null;
  let redirect = "follow";
  if (typeof input === "string") {
    url = checkUrl(resolveUrl(input));
  } else {
    const rec = asRec(input);
    if (!rec) throw new TypeError("fetch(input)");
    url = checkUrl(resolveUrl(String(rec["url"] ?? "")));
    if (rec["method"] != null) method = checkMethod(String(rec["method"])).toUpperCase();
    if (rec["headers"] != null) headers = makeHeaders(rec["headers"]);
    if (rec["body"] != null) body = asBody(rec["body"]);
    if (rec["signal"] != null) signal = rec["signal"] as AbortSignalJs;
  }
  const extra = asRec(init);
  if (extra) {
    if (extra["method"] != null) method = checkMethod(String(extra["method"])).toUpperCase();
    if (extra["headers"] != null) headers = makeHeaders(extra["headers"]);
    if (extra["body"] != null) body = asBody(extra["body"]);
    if (extra["signal"] != null) signal = extra["signal"] as AbortSignalJs;
    if (extra["redirect"] != null) redirect = String(extra["redirect"]);
  }
  return { url, method, headers, body, signal, redirect };
}

function RequestImpl(this: RequestJs, input: unknown, init?: unknown) {
  const p = parseInput(input, init);
  this["url"] = p.url;
  this["method"] = p.method;
  this["headers"] = p.headers;
  this["body"] = p.body;
  this["signal"] = p.signal;
}

function deniedJsResponse(url: string): FetchResponseJs {
  const out = Object.create(ResponseImpl.prototype) as FetchResponseJs;
  const bytes = utf8Encode("");
  fillResponse(out, 404, "Not Found", url, makeHeaders(), makeStream(memoryPull(bytes)), false);
  out._bytes = bytes;
  out._json = null;
  return out;
}

async function fetchImpl(input: unknown, init?: unknown): Promise<FetchResponseJs> {
  const p = parseInput(input, init);
  if (!p.url) throw new TypeError("fetch: invalid URL");
  if (!allowEngineJsUrl(p.url)) {
    debug(function () {
      return "js deny " + p.url;
    });
    return deniedJsResponse(p.url);
  }
  const signal = p.signal;
  if (signal && signal["aborted"]) throw abortError();
  const builder = new HttpRequestBuilderImpl(p.method, p.url);
  p.headers["forEach"](function (value, name) {
    builder.addHeader(name, value);
  });
  const loc = (globalThis as unknown as Record<string, unknown>)["location"] as
    Record<string, unknown> | undefined;
  if (!p.headers["has"]("referer") && loc && loc["href"] != null) {
    builder.addHeader("Referer", String(loc["href"]));
  }
  if (!p.headers["has"]("origin") && loc && loc["origin"] != null) {
    builder.addHeader("Origin", String(loc["origin"]));
  }
  if (p.body !== undefined) {
    builder.body(p.body);
  } else if (p.method === "POST" || p.method === "PUT" || p.method === "PATCH") {
    builder.body("");
  }
  if (p.redirect === "manual" || p.redirect === "error") builder.followRedirects(false);
  /* Cookie always (browser). SAPISIDHASH only for youtubei that did not already send Authorization. */
  let auth: AuthContext | undefined = undefined;
  if (isYoutubeiUrl(p.url) && !p.headers["has"]("authorization")) {
    const session = await getSession(SESSION_MAX_AGE_MS);
    auth = {
      sessionIndex: session.sessionIndex,
      delegatedSessionId: session.delegatedSessionId,
      userSessionId: session.userSessionId,
    };
  }
  await attachRequestIdentity(p.url, builder, auth);
  const call = builder.build();
  const onAbort = function () {
    call.cancel();
  };
  if (signal && typeof signal["addEventListener"] === "function") {
    signal["addEventListener"]("abort", onAbort);
  }
  let detached = false;
  const detach = function () {
    if (detached) return;
    detached = true;
    if (signal && typeof signal["removeEventListener"] === "function") {
      signal["removeEventListener"]("abort", onAbort);
    }
  };
  let httpRes: HttpResponse;
  try {
    httpRes = await call.execute();
  } catch (e) {
    detach();
    if (signal && signal["aborted"]) throw abortError();
    throw failedToFetch(e);
  }
  if (signal && signal["aborted"]) {
    httpRes.cancel();
    detach();
    throw abortError();
  }
  await storeSetCookies(httpRes.url.length > 0 ? httpRes.url : p.url, httpRes);
  const pull = httpPull(httpRes, detach);
  const cancelSrc = function () {
    httpRes.cancel();
    detach();
  };
  const out = Object.create(ResponseImpl.prototype) as FetchResponseJs;
  fillResponse(
    out,
    httpRes.status,
    httpRes.statusText,
    httpRes.url,
    makeHeaders(httpRes.headers.entries),
    makeStream(pull, cancelSrc),
    httpRes.url !== p.url,
  );
  out._bytes = null;
  out._json = null;
  out._http = httpRes;
  return out;
}

export function installFetch(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  g["Headers"] = HeadersImpl;
  g["Request"] = RequestImpl;
  g["Response"] = ResponseImpl;
  g["ReadableStream"] = ReadableStreamImpl;
  g["ReadableStreamDefaultReader"] = ReaderImpl;
  g["AbortSignal"] = AbortSignalImpl;
  g["AbortController"] = AbortControllerImpl;
  g["fetch"] = native("fetch", fetchImpl);
}

installFetch();
