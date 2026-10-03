import { HttpCallImpl } from "./call";
import {
  checkHeaderName,
  checkHeaderValue,
  checkMethod,
  checkUrl,
  hasHeader,
  normalizeHeaders,
  sanitizeHeaderName,
  sanitizeHeaderValue,
} from "./headers";
import { utf8ByteLength } from "./util";
import type { JsLogger } from "../logger";
import type { BodyKind, RequestOpts } from "./wire";

export class HttpRequestBuilderImpl implements HttpRequestBuilder {
  private _method: string;
  private _url: string;
  private _headers: HeaderEntry[] = []; // ordered multimap: [[name, value], …] (addHeader can repeat a name)
  private _body: HttpBody | null = null;
  private _timeoutMs?: number;
  private _followRedirects?: boolean;
  private _log?: JsLogger;

  constructor(method: string, url: string) {
    this._method = checkMethod(method);
    this._url = checkUrl(url);
  }
  // Set: replace any existing same-name header (case-insensitive), then append.
  setHeader(name: string, value: string): this {
    name = checkHeaderName(name);
    const lower = name.toLowerCase();
    this._headers = this._headers.filter(function (e) {
      return String(e[0]).toLowerCase() !== lower;
    });
    this._headers.push([name, checkHeaderValue(value)]);
    return this;
  }
  // Append: keep any existing same-name header (for genuine multi-value headers).
  addHeader(name: string, value: string): this {
    this._headers.push([checkHeaderName(name), checkHeaderValue(value)]);
    return this;
  }
  // setHeader()/addHeader() for untrusted (scraped) data: coerce into a legal header, never
  // throw (except a name with no valid characters at all).
  setHeaderSanitized(name: string, value: string): this {
    return this.setHeader(sanitizeHeaderName(name), sanitizeHeaderValue(value));
  }
  addHeaderSanitized(name: string, value: string): this {
    return this.addHeader(sanitizeHeaderName(name), sanitizeHeaderValue(value));
  }
  // Bulk set from an object or an [[name, value], …] list (later same-name entries win).
  headers(h: HeaderEntry[] | Record<string, string>): this {
    for (const [name, value] of normalizeHeaders(h)) this.setHeader(name, value);
    return this;
  }
  // Body: a string, a Uint8Array, or an (io) => … callback that writes to the buffered sink.
  body(value: HttpBody): this {
    this._body = value;
    return this;
  }
  // Sugar: write value as a JSON body, defaulting Content-Type when the caller hasn't set one.
  json(value: unknown): this {
    if (!hasHeader(this._headers, "content-type"))
      this.setHeader("Content-Type", "application/json");
    this._body = function (io: PipeSink) {
      return io.writeObjectAsJson(value);
    }; // returned promise is awaited by execute()
    return this;
  }
  // Deadline until headers return, and until the body is first consumed, in milliseconds
  // (server default otherwise). A streaming read after the first consume is not cut off.
  timeout(ms: number): this {
    if (!Number.isFinite(ms) || ms <= 0)
      throw new RangeError("timeout must be a positive number of ms");
    this._timeoutMs = Math.floor(ms);
    return this;
  }
  // Follow (or not) redirects for this request (server default otherwise).
  followRedirects(follow: boolean): this {
    this._followRedirects = !!follow;
    return this;
  }
  // The caller's crumb-trail logger — rides the head as {"key","trail"} so host birth-site logs
  // (a request/response-body failure) join the Kotlin caller's parked trail (logger.ts).
  // Developer-only (scope labels, never data/PII) — it never reaches the user; the error code does.
  logger(l: JsLogger): this {
    this._log = l;
    return this;
  }

  // Snapshot this builder into an executable, cancellable call.
  build(): HttpCall {
    const headers = this._headers.slice();
    const body = this._body;
    let kind: BodyKind = null;
    let len = -1;
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
    // Wire keys bracketed — opts rides the head to Kotlin, so the keys must survive Closure.
    let opts: RequestOpts | null = null;
    if (this._timeoutMs !== undefined) (opts ??= {})["timeoutMs"] = this._timeoutMs;
    if (this._followRedirects !== undefined)
      (opts ??= {})["followRedirects"] = this._followRedirects;
    return new HttpCallImpl(
      this._method,
      this._url,
      headers,
      kind,
      body,
      len,
      opts,
      this._log || null,
    );
  }
}
