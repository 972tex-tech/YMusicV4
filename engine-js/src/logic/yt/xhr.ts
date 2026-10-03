// XMLHttpRequest over HttpRequest, for eval'd base.js. Same place as fetch.ts: Cookie goes on the
// builder via cookieHeaderFor. Player ads strip is an HttpCall interceptor (skip_ads.ts).
// Quoted names so Closure ADVANCED leaves the browser-shaped API intact.

import { debug } from "../../debug";
import { utf8Encode } from "../../transport";
import { HttpRequestBuilderImpl } from "../../protocol/http/builder";
import { resolveUrl } from "../../protocol/http/headers";
import { parsedJson } from "../../protocol/http/intercept";
import { installJsonParseHook, taggedJsonText } from "../../protocol/http/tagged";
import { isPlayerUrl } from "../player/skip_ads";
import { attachRequestIdentity, isYoutubeiUrl, storeSetCookies } from "./cookies";
import type { AuthContext } from "./cookies";
import { getSession, SESSION_MAX_AGE_MS } from "./session";
import { logSabrRequest, logSabrResponse } from "./sabr_log";
import { transformRequestBody, transformResponseBody } from "./sabr_abr";
import type { SabrRequestState } from "./sabr_abr";
import { noteSabrPlaybackUrl } from "../player/player_res";
import { allowEngineJsUrl } from "./jsAllow";

type Listener = (ev?: Record<string, unknown>) => void;

interface XhrJs {
  readyState: number;
  status: number;
  statusText: string;
  response: unknown;
  responseText: string;
  responseType: string;
  timeout: number;
  withCredentials: boolean;
  onreadystatechange: Listener | null;
  onload: Listener | null;
  onerror: Listener | null;
  ontimeout: Listener | null;
  onabort: Listener | null;
  _method: string;
  _url: string;
  _headers: Array<[string, string]>;
  _respHeaders: Array<[string, string]>;
  _listeners: Record<string, Listener[]>;
  _aborted: boolean;
  _call: HttpCall | null;
}

function native(name: string, impl: Function): Function {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl);
  return impl;
}

export { taggedJsonText };

/** InnerTube /player JSON (ads interceptor already rewrote). Never streaming SABR. */
export function wantsJsonRewrite(url: string): boolean {
  return isPlayerUrl(url);
}

function fire(x: XhrJs, type: string): void {
  const ev: Record<string, unknown> = {
    "type": type,
    "target": x,
    "currentTarget": x,
    "bubbles": false,
    "cancelable": false,
  };
  const list = x._listeners[type];
  if (list) {
    for (let i = 0; i < list.length; i++) {
      try {
        list[i]!.call(x, ev);
      } catch (_e) {}
    }
  }
  const key = "on" + type;
  const fn = (x as unknown as Record<string, unknown>)[key];
  if (typeof fn === "function") {
    try {
      (fn as Listener).call(x, ev);
    } catch (_e) {}
  }
}

function setReady(x: XhrJs, state: number): void {
  x["readyState"] = state;
  fire(x, "readystatechange");
}

async function drainBytes(res: HttpResponse): Promise<Uint8Array> {
  const stream = res.stream();
  const parts: Uint8Array[] = [];
  let total = 0;
  const buf = new Uint8Array(8192);
  for (;;) {
    const n = await stream.read(buf, 0, buf.length);
    if (n < 0) break;
    parts.push(buf.slice(0, n));
    total += n;
  }
  const out = new Uint8Array(total);
  let off = 0;
  for (let i = 0; i < parts.length; i++) {
    out.set(parts[i]!, off);
    off += parts[i]!.length;
  }
  return out;
}

function XhrImpl(this: XhrJs): XhrJs {
  const self = this && this !== (globalThis as unknown) ? this : Object.create(XhrImpl.prototype);
  self["readyState"] = 0;
  self["status"] = 0;
  self["statusText"] = "";
  self["response"] = null;
  self["responseText"] = "";
  self["responseType"] = "";
  self["timeout"] = 0;
  self["withCredentials"] = false;
  self["onreadystatechange"] = null;
  self["onload"] = null;
  self["onerror"] = null;
  self["ontimeout"] = null;
  self["onabort"] = null;
  self._method = "GET";
  self._url = "";
  self._headers = [];
  self._respHeaders = [];
  self._listeners = {};
  self._aborted = false;
  self._call = null;
  const proto = XhrImpl.prototype as unknown as Record<string, unknown>;
  const names = [
    "open",
    "setRequestHeader",
    "send",
    "abort",
    "addEventListener",
    "removeEventListener",
    "getResponseHeader",
    "getAllResponseHeaders",
  ];
  for (let i = 0; i < names.length; i++) {
    const n = names[i]!;
    (self as unknown as Record<string, unknown>)[n] = proto[n];
  }
  return self;
}

XhrImpl["UNSENT"] = 0;
XhrImpl["OPENED"] = 1;
XhrImpl["HEADERS_RECEIVED"] = 2;
XhrImpl["LOADING"] = 3;
XhrImpl["DONE"] = 4;
XhrImpl.prototype["UNSENT"] = 0;
XhrImpl.prototype["OPENED"] = 1;
XhrImpl.prototype["HEADERS_RECEIVED"] = 2;
XhrImpl.prototype["LOADING"] = 3;
XhrImpl.prototype["DONE"] = 4;

XhrImpl.prototype["open"] = function (this: XhrJs, method: string, url: string): void {
  this._method = String(method || "GET").toUpperCase();
  this._url = resolveUrl(String(url || ""));
  this._headers = [];
  this._aborted = false;
  this._call = null;
  setReady(this, 1);
};

XhrImpl.prototype["setRequestHeader"] = function (this: XhrJs, name: string, value: string): void {
  this._headers.push([String(name), String(value)]);
};

XhrImpl.prototype["addEventListener"] = function (this: XhrJs, type: string, fn: Listener): void {
  if (typeof fn !== "function") return;
  const bag = this._listeners;
  (bag[type] || (bag[type] = [])).push(fn);
};

XhrImpl.prototype["removeEventListener"] = function (
  this: XhrJs,
  type: string,
  fn: Listener,
): void {
  const list = this._listeners[type];
  if (!list) return;
  const i = list.indexOf(fn);
  if (i >= 0) list.splice(i, 1);
};

XhrImpl.prototype["abort"] = function (this: XhrJs): void {
  this._aborted = true;
  if (this._call) this._call.cancel();
  fire(this, "abort");
};

XhrImpl.prototype["getResponseHeader"] = function (this: XhrJs, name: string): string | null {
  const lower = String(name).toLowerCase();
  for (let i = 0; i < this._respHeaders.length; i++) {
    if (this._respHeaders[i]![0].toLowerCase() === lower) return this._respHeaders[i]![1];
  }
  return null;
};

XhrImpl.prototype["getAllResponseHeaders"] = function (this: XhrJs): string {
  let s = "";
  for (let i = 0; i < this._respHeaders.length; i++) {
    const e = this._respHeaders[i]!;
    s += e[0] + ": " + e[1] + "\r\n";
  }
  return s;
};

XhrImpl.prototype["send"] = function (this: XhrJs, body?: unknown): void {
  const self = this;
  const url = self._url;
  noteSabrPlaybackUrl(url);
  if (!allowEngineJsUrl(url)) {
    debug(function () {
      return "js deny " + url;
    });
    Promise.resolve().then(function () {
      if (self._aborted) return;
      self["status"] = 404;
      self["statusText"] = "Not Found";
      self["responseText"] = "";
      self["response"] = "";
      setReady(self, 2);
      setReady(self, 3);
      setReady(self, 4);
      fire(self, "load");
    });
    return;
  }
  const builder = new HttpRequestBuilderImpl(self._method, url);
  for (let i = 0; i < self._headers.length; i++) {
    const h = self._headers[i]!;
    builder.addHeader(h[0], h[1]);
  }
  const loc = (globalThis as unknown as Record<string, unknown>)["location"] as
    Record<string, unknown> | undefined;
  let hasReferer = false;
  let hasOrigin = false;
  for (let i = 0; i < self._headers.length; i++) {
    const n = self._headers[i]![0].toLowerCase();
    if (n === "referer") hasReferer = true;
    if (n === "origin") hasOrigin = true;
  }
  if (!hasReferer && loc && loc["href"] != null) builder.addHeader("Referer", String(loc["href"]));
  if (!hasOrigin && loc && loc["origin"] != null)
    builder.addHeader("Origin", String(loc["origin"]));
  const sabr = transformRequestBody(url, body);
  const sabrState: SabrRequestState | null = sabr.state;
  body = sabr.body;
  logSabrRequest(url, body);
  if (body != null && body !== "") {
    if (typeof body === "string") builder.body(body);
    else if (body instanceof Uint8Array) builder.body(body);
    else builder.body(String(body));
  } else if (self._method === "POST" || self._method === "PUT" || self._method === "PATCH") {
    builder.body("");
  }
  const ms = self["timeout"];
  if (typeof ms === "number" && ms > 0) builder.timeout(ms);
  (async function () {
    try {
      /* Cookie always (browser). SAPISIDHASH only for youtubei that did not already send Authorization. */
      let auth: AuthContext | undefined = undefined;
      if (isYoutubeiUrl(url)) {
        let hasAuth = false;
        for (let i = 0; i < self._headers.length; i++) {
          if (self._headers[i]![0].toLowerCase() === "authorization") hasAuth = true;
        }
        if (!hasAuth) {
          const session = await getSession(SESSION_MAX_AGE_MS);
          auth = {
            sessionIndex: session.sessionIndex,
            delegatedSessionId: session.delegatedSessionId,
            userSessionId: session.userSessionId,
          };
        }
      }
      await attachRequestIdentity(url, builder, auth);
      if (self._aborted) return;
      const call = builder.build();
      self._call = call;
      const res = await call.execute();
      if (self._aborted) return;
      await storeSetCookies(res.url.length > 0 ? res.url : url, res);
      if (self._aborted) return;
      self["status"] = res.status;
      self["statusText"] = res.statusText;
      logSabrRequest(url, body, res.status);
      self._respHeaders = res.headers.entries.slice();
      setReady(self, 2);
      if (wantsJsonRewrite(url)) {
        const rec = parsedJson(res) || (await res.jsonObject());
        if (self["responseType"] === "json") {
          self["response"] = rec;
          self["responseText"] = "";
        } else if (self["responseType"] === "arraybuffer") {
          const u8 = utf8Encode(JSON.stringify(rec));
          const copy = new Uint8Array(u8.byteLength);
          copy.set(u8);
          self["response"] = copy.buffer;
          self["responseText"] = "";
        } else {
          const s = taggedJsonText(rec);
          self["responseText"] = s;
          self["response"] = s;
        }
      } else if (self["responseType"] === "arraybuffer") {
        let u8 = await drainBytes(res);
        u8 = transformResponseBody(u8, sabrState);
        logSabrResponse(url, res.status, u8);
        const copy = new Uint8Array(u8.byteLength);
        copy.set(u8);
        self["response"] = copy.buffer;
        self["responseText"] = "";
      } else {
        const text = await res.text();
        self["responseText"] = text;
        self["response"] = text;
      }
      if (self._aborted) return;
      setReady(self, 3);
      setReady(self, 4);
      fire(self, "load");
    } catch (_e) {
      if (self._aborted) return;
      setReady(self, 4);
      fire(self, "error");
    }
  })();
};

export function installXhr(): void {
  installJsonParseHook();
  const g = globalThis as unknown as Record<string, unknown>;
  const Ctor = native("XMLHttpRequest", XhrImpl);
  (Ctor as { prototype: unknown }).prototype = XhrImpl.prototype;
  const rec = Ctor as unknown as Record<string, unknown>;
  rec["UNSENT"] = 0;
  rec["OPENED"] = 1;
  rec["HEADERS_RECEIVED"] = 2;
  rec["LOADING"] = 3;
  rec["DONE"] = 4;
  g["XMLHttpRequest"] = Ctor;
}

installXhr();
