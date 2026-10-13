// OkHttp-shaped interceptors on HttpCall.execute. fetch/XHR stay thin wrappers of HttpRequest.
// Each interceptor may rewrite the request (url/headers/body) and/or wrap the response.

import { utf8Decode } from "../../transport";
import type { JsLogger } from "../logger";
import type { RequestOpts } from "./wire";

export type HttpReq = {
  method: string;
  url: string;
  headers: HeaderEntry[];
  body: HttpBody | null;
  opts: RequestOpts | null;
  log: JsLogger | null;
};

export type HttpChain = {
  request: HttpReq;
  proceed: (req?: HttpReq) => Promise<HttpResponse>;
};

export type HttpInterceptor = (chain: HttpChain) => Promise<HttpResponse>;

const interceptors: HttpInterceptor[] = [];

export function addInterceptor(fn: HttpInterceptor): { remove(): void } {
  interceptors.push(fn);
  return {
    remove: function () {
      const i = interceptors.indexOf(fn);
      if (i >= 0) interceptors.splice(i, 1);
    },
  };
}

export function dispatchHttp(
  req: HttpReq,
  send: (req: HttpReq) => Promise<HttpResponse>,
): Promise<HttpResponse> {
  return run(0, req, send);
}

function run(
  index: number,
  req: HttpReq,
  send: (req: HttpReq) => Promise<HttpResponse>,
): Promise<HttpResponse> {
  if (index >= interceptors.length) return send(req);
  const fn = interceptors[index]!;
  return fn({
    request: req,
    proceed: function (next?: HttpReq): Promise<HttpResponse> {
      return run(index + 1, next || req, send);
    },
  });
}

/** A body an interceptor rewrote, held in memory. Read-once, like a network response. */
export class BufferedResponse implements HttpResponse {
  readonly status: number;
  readonly statusText: string;
  readonly url: string;
  readonly ok: boolean;
  readonly headers: HttpResponse["headers"];
  readonly cache: string | undefined;
  private _inner: HttpResponse;
  private _body: Uint8Array | null;

  constructor(inner: HttpResponse, body: Uint8Array) {
    this._inner = inner;
    this.status = inner.status;
    this.statusText = inner.statusText;
    this.url = inner.url;
    this.ok = inner.ok;
    this.headers = inner.headers;
    this.cache = inner.cache;
    this._body = body;
  }

  private _take(): Uint8Array {
    const body = this._body;
    if (body === null) throw new Error("Response body has already been consumed");
    this._body = null;
    return body;
  }

  async jsonObject(): Promise<Record<string, unknown>> {
    const v = __parseJson(this._take());
    if (v === null || typeof v !== "object" || Array.isArray(v)) {
      throw new TypeError("response body is not a JSON object");
    }
    return v as Record<string, unknown>;
  }
  async jsonArray(): Promise<unknown[]> {
    const v = __parseJson(this._take());
    if (!Array.isArray(v)) throw new TypeError("response body is not a JSON array");
    return v;
  }
  async text(): Promise<string> {
    return utf8Decode(this._take());
  }
  async bytes(): Promise<Uint8Array> {
    return this._take();
  }
  stream(): HttpBodyStream {
    const bytes = this._take();
    let off = 0;
    return {
      read: function (buffer, offset, len) {
        if (off >= bytes.length) return Promise.resolve(-1);
        const n = Math.min(len, bytes.length - off);
        buffer.set(bytes.subarray(off, off + n), offset);
        off += n;
        return Promise.resolve(n);
      },
    };
  }
  cancel(): void {
    this._body = null;
    this._inner.cancel();
  }
  close(): void {
    this.cancel();
  }
}
