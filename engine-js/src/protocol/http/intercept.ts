// OkHttp-shaped interceptors on HttpCall.execute. fetch/XHR stay thin wrappers of HttpRequest.
// Each interceptor may rewrite the request (url/headers/body) and/or wrap the response.

import { stringifyJson } from "../../transport";
import type { JsLogger } from "../logger";
import { taggedJsonText } from "./tagged";
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

/** Response whose JSON was already parsed (and possibly rewritten) by an interceptor. */
export class ParsedJsonResponse implements HttpResponse {
  readonly status: number;
  readonly statusText: string;
  readonly url: string;
  readonly ok: boolean;
  readonly headers: HttpResponse["headers"];
  readonly record: Record<string, unknown>;
  private _inner: HttpResponse;

  constructor(inner: HttpResponse, record: Record<string, unknown>) {
    this._inner = inner;
    this.status = inner.status;
    this.statusText = inner.statusText;
    this.url = inner.url;
    this.ok = inner.ok;
    this.headers = inner.headers;
    this.record = record;
  }

  jsonObject(): Promise<Record<string, unknown>> {
    return Promise.resolve(this.record);
  }
  jsonArray(): Promise<unknown[]> {
    return Promise.reject(new TypeError("ParsedJsonResponse is an object"));
  }
  text(): Promise<string> {
    return Promise.resolve(taggedJsonText(this.record) as unknown as string);
  }
  bytes(): Promise<Uint8Array> {
    return Promise.resolve(stringifyJson(this.record));
  }
  stream(): HttpBodyStream {
    const bytes = stringifyJson(this.record);
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
    this._inner.cancel();
  }
  ignoreResult(): void {}
}
