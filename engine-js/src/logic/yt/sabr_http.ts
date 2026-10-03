// SABR /videoplayback as an HttpCall interceptor so fetch/XHR stay thin HttpRequest wrappers.
// Request: rewrite ABR body. Response: tee UMP on stream()/bytes() and consume fakeUmpAfter parts at EOF.

import { utf8ByteLength } from "../../protocol/http/util";
import { addInterceptor } from "../../protocol/http/intercept";
import type { HttpChain, HttpReq } from "../../protocol/http/intercept";
import { noteSabrPlaybackUrl } from "../player/player_res";
import { fakeUmpAfter, transformRequestBody } from "./sabr_abr";
import type { SabrRequestState } from "./sabr_abr";
import { createSabrTee, logSabrRequest } from "./sabr_log";

function isSabrUrl(url: string): boolean {
  return url.indexOf("/videoplayback") >= 0;
}

function rewriteReq(req: HttpReq): { req: HttpReq; state: SabrRequestState | null } {
  const sabr = transformRequestBody(req.url, req.body);
  logSabrRequest(req.url, sabr.body);
  if (sabr.body === req.body) return { req: req, state: sabr.state };
  const body = sabr.body;
  if (typeof body !== "string" && !(body instanceof Uint8Array)) {
    return { req: req, state: sabr.state };
  }
  const headers = req.headers.filter(function (e) {
    return String(e[0]).toLowerCase() !== "content-length";
  });
  const len = typeof body === "string" ? utf8ByteLength(body) : body.length;
  headers.push(["Content-Length", String(len)]);
  return {
    req: Object.assign({}, req, { headers: headers, body: body }),
    state: sabr.state,
  };
}

function concatHead(head: Uint8Array, parts: Uint8Array[]): Uint8Array {
  let n = head.length;
  for (let i = 0; i < parts.length; i++) n += parts[i]!.length;
  const out = new Uint8Array(n);
  out.set(head, 0);
  let off = head.length;
  for (let i = 0; i < parts.length; i++) {
    out.set(parts[i]!, off);
    off += parts[i]!.length;
  }
  return out;
}

function wrapSabrStream(
  inner: HttpBodyStream,
  state: SabrRequestState | null,
  url: string,
  status: number,
): HttpBodyStream {
  const tee = createSabrTee();
  let extra: Uint8Array[] | null = null;
  let extraI = 0;
  let extraOff = 0;
  let innerEof = false;
  let fakeSent = false;
  let finished = false;
  function end(): void {
    if (finished) return;
    finished = true;
    tee.finish(url, status);
  }
  function readExtra(buffer: Uint8Array, offset: number, len: number): number {
    if (!extra) return -1;
    let wrote = 0;
    while (wrote < len && extraI < extra.length) {
      const p = extra[extraI]!;
      if (extraOff >= p.length) {
        extraI++;
        extraOff = 0;
        continue;
      }
      const n = Math.min(len - wrote, p.length - extraOff);
      buffer.set(p.subarray(extraOff, extraOff + n), offset + wrote);
      extraOff += n;
      wrote += n;
      if (extraOff >= p.length) {
        extraI++;
        extraOff = 0;
      }
    }
    if (extraI >= extra.length) extra = null;
    return wrote > 0 ? wrote : -1;
  }
  return {
    read: async function (buffer, offset, len) {
      if (extra) {
        const n = readExtra(buffer, offset, len);
        if (n > 0) return n;
      }
      if (!innerEof) {
        const n = await inner.read(buffer, offset, len);
        if (n > 0) {
          tee.push(buffer.slice(offset, offset + n));
          return n;
        }
        if (n === 0) return 0;
        innerEof = true;
      }
      if (!fakeSent) {
        fakeSent = true;
        const fake = fakeUmpAfter(state, tee.maxHid());
        if (fake && fake.length) {
          extra = fake;
          extraI = 0;
          extraOff = 0;
          tee.consume(fake);
          const m = readExtra(buffer, offset, len);
          if (m > 0) return m;
        }
      }
      end();
      return -1;
    },
  };
}

class SabrResponse implements HttpResponse {
  readonly status: number;
  readonly statusText: string;
  readonly url: string;
  readonly ok: boolean;
  readonly headers: HttpResponse["headers"];
  private _inner: HttpResponse;
  private _state: SabrRequestState | null;
  private _reqUrl: string;

  constructor(inner: HttpResponse, state: SabrRequestState | null, reqUrl: string) {
    this._inner = inner;
    this._state = state;
    this._reqUrl = reqUrl;
    this.status = inner.status;
    this.statusText = inner.statusText;
    this.url = inner.url;
    this.ok = inner.ok;
    this.headers = inner.headers;
  }

  jsonObject(): Promise<Record<string, unknown>> {
    return this._inner.jsonObject();
  }
  jsonArray(): Promise<unknown[]> {
    return this._inner.jsonArray();
  }
  text(): Promise<string> {
    return this._inner.text();
  }
  async bytes(): Promise<Uint8Array> {
    const u8 = await this._inner.bytes();
    const tee = createSabrTee();
    tee.push(u8);
    const fake = fakeUmpAfter(this._state, tee.maxHid());
    if (fake && fake.length) tee.consume(fake);
    tee.finish(this._reqUrl, this.status);
    if (!fake || !fake.length) return u8;
    return concatHead(u8, fake);
  }
  stream(): HttpBodyStream {
    return wrapSabrStream(this._inner.stream(), this._state, this._reqUrl, this.status);
  }
  cancel(): void {
    this._inner.cancel();
  }
  close(): void {
    this.cancel();
  }
}

async function sabrIntercept(chain: HttpChain): Promise<HttpResponse> {
  const req0 = chain.request;
  if (!isSabrUrl(req0.url)) return chain.proceed();
  noteSabrPlaybackUrl(req0.url);
  const rewritten = rewriteReq(req0);
  const res = await chain.proceed(rewritten.req);
  logSabrRequest(req0.url, rewritten.req.body, res.status);
  return new SabrResponse(res, rewritten.state, req0.url);
}

let installed = false;

export function installSabrIntercept(): void {
  if (installed) return;
  installed = true;
  addInterceptor(sabrIntercept);
}
