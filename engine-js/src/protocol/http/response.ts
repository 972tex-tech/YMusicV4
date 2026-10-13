import { buildHeaders } from "./headers";
import { hostError } from "../command";
import { swallow } from "./util";

// Matches kmpHttp's defaultExecuteTimeout. An unused body (never text/bytes/json/stream/cancel)
// is aborted after this, even when the builder did not set timeoutMs. First consume clears it so a
// streaming read (SABR) is not cut off.
const UNUSED_BODY_MS = 15000;

type HttpLife = {
  abort: () => void;
  finish: (reason: string) => void;
  stopIdle: () => void;
};

function httpLife(io: Pipe, retire: (reason: string) => void, timeoutMs: number): HttpLife {
  let done = false;
  let idle: number | undefined;
  const stopIdle = function () {
    const t = idle;
    if (t === undefined) return;
    idle = undefined;
    clearTimeout(t);
  };
  const life: HttpLife = {
    abort: function () {
      stopIdle();
      if (!done) {
        done = true;
        io.sink.close("cancelled"); // RESET: legal at any stage, never flushes
        retire("cancelled");
      }
      io.source.close();
    },
    finish: function (reason: string) {
      stopIdle();
      if (done) return;
      done = true;
      swallow(io.sink.close());
      retire(reason);
    },
    stopIdle: stopIdle,
  };
  if (timeoutMs > 0) {
    idle = setTimeout(function () {
      idle = undefined;
      life.abort();
    }, timeoutMs);
  }
  return life;
}

const httpGc: FinalizationRegistry<HttpLife> | null =
  typeof FinalizationRegistry === "function"
    ? new FinalizationRegistry<HttpLife>(function (life) {
        life.abort();
      })
    : null;

// A pull reader over the response body.
class BodyStream implements HttpBodyStream {
  private _res: Response;

  constructor(res: Response) {
    this._res = res;
  }
  // Read up to len bytes into buffer at offset; resolves with the count, or -1 at EOF.
  read(buffer: Uint8Array, offset: number, len: number): Promise<number> {
    return this._res._read(buffer, offset, len);
  }
}

export class Response implements HttpResponse {
  readonly status: number;
  readonly statusText: string;
  readonly url: string; // final URL, after redirects
  readonly ok: boolean;
  readonly headers: ReturnType<typeof buildHeaders>;
  readonly cache: string | undefined;
  private _io: Pipe;
  private _life: HttpLife;
  private _consumed = false;
  private _eof = false;

  constructor(
    status: number,
    statusText: string,
    url: string,
    headers: HeaderEntry[],
    io: Pipe,
    onFinished: (reason: string) => void,
    timeoutMs?: number,
    cache?: string,
  ) {
    this.status = status;
    this.statusText = statusText;
    this.url = url;
    this.ok = status >= 200 && status < 300;
    this.headers = buildHeaders(headers);
    this.cache = cache;
    this._io = io;
    const life = httpLife(io, onFinished, timeoutMs !== undefined ? timeoutMs : UNUSED_BODY_MS);
    this._life = life;
    if (httpGc) httpGc.register(this, life, this);
  }
  private _unregister(): void {
    this._life.stopIdle();
    if (httpGc) httpGc.unregister(this);
  }
  private _take(): void {
    if (this._consumed) throw new Error("Response body has already been consumed");
    this._consumed = true;
    this._life.stopIdle();
  }
  // The exchange is over: half-close our write side (the cancel channel) so the server's
  // watcher sees EOF and the pipe reaps, and retire the call's body plumbing.
  private _end(): void {
    if (this._eof) return;
    this._eof = true;
    this._unregister();
    this._life.finish("request finished");
  }
  // A failed body read (server RESET — e.g. "response body failed: …" when the stream died
  // after the head): the stream is unusable past this point, so retire everything and drop our
  // read end before rethrowing.
  private _fail(): void {
    this._end();
    this._io.source.close();
  }
  // Raw read — the body is the rest of the control pipe's inbound stream, EOF-delimited.
  async _read(buffer: Uint8Array, offset: number, len: number): Promise<number> {
    if (this._eof) return -1;
    let n: number;
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
  async text(): Promise<string> {
    this._take();
    let s: string;
    try {
      s = await this._io.source.readUtf8();
    } catch (e) {
      this._fail();
      throw hostError(e);
    }
    this._end();
    return s;
  }
  // Whole body as raw bytes — one C accumulator, stolen into a tight Uint8Array at EOF.
  async bytes(): Promise<Uint8Array> {
    this._take();
    let u8: Uint8Array;
    try {
      u8 = await this._io.source.readBytes();
    } catch (e) {
      this._fail();
      throw hostError(e);
    }
    this._end();
    return u8;
  }
  // Parse the body with the STREAMING JSON parser, validating the root type: jsonObject() requires
  // a `{…}` body, jsonArray() a `[…]` (readJsonObject/readJsonArray reject a mismatched root on the
  // first byte). Object vs array is the caller's to pick, not guessed.
  jsonObject(): Promise<Record<string, unknown>> {
    return this._readJson((src) => src.readJsonObject());
  }
  jsonArray(): Promise<unknown[]> {
    return this._readJson((src) => src.readJsonArray());
  }
  // Chunk-by-chunk, never materializing the whole body as a string (that's what the streaming
  // parser is for). A read failure -> network; malformed JSON -> the parser's own error (classified
  // "parse"). The parser stops at the value's end, so drain to EOF to reap the read side like
  // text() — but the body must be ONE JSON value: any non-whitespace tail is a corrupt/misframed
  // body (SyntaxError -> "parse", naming the offending byte), not silently eaten.
  private async _readJson<T>(read: (src: PipeSource) => MaybePromise<T>): Promise<T> {
    this._take();
    let v: T;
    try {
      v = await read(this._io.source);
      const scratch = new Uint8Array(64);
      for (;;) {
        const n = await this._io.source.receive(scratch, 0, scratch.length);
        if (n < 0) break; // EOF
        for (let i = 0; i < n; i++) {
          const b = scratch[i]!; // i < n <= scratch.length
          if (b !== 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0d) {
            const hex = "0x" + b.toString(16).padStart(2, "0");
            const shown = b >= 0x20 && b < 0x7f ? " '" + String.fromCharCode(b) + "'" : "";
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
  stream(): HttpBodyStream {
    this._take();
    return new BodyStream(this);
  }
  // Abort the body: a RESET of the still-open write side cancels the exchange server-side even if
  // it is parked inside the transport; closeRead drops buffered bytes and EPIPEs any in-flight
  // server write. Safe at any point, even mid-stream() or after consumption.
  cancel(): void {
    this._consumed = true;
    this._eof = true;
    this._unregister();
    this._life.abort();
  }
  close(): void {
    this.cancel();
  }
}
