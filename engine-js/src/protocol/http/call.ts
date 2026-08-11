import { open, onAccept } from "../../transport";
import { hostError } from "../command";
import { Response } from "./response";
import type { BodyKind, RequestHead, RequestOpts } from "./wire";

// One request execution. execute() resolves the Response (once); cancel() aborts at ANY stage:
// before execute() (it refuses to start), mid-flight (a RESET of the control pipe's request
// direction — the server watcher treats it like the in-band -1 and aborts the exchange, even
// one parked inside its transport with the body never pulled; execute() rejects
// "request cancelled"), or after the Response (delegates to Response.cancel()).
export class HttpCallImpl implements HttpCall {
  private _method: string;
  private _url: string;
  private _headers: HeaderEntry[];
  private _kind: BodyKind;
  private _body: HttpBody | null;
  private _len: number;
  private _opts: RequestOpts | null;
  private _logCtx: string[] | null; // the caller's logging-context trail for birth-site logs
  private _io: Pipe | null = null;
  private _response: Response | null = null;
  private _bodyReg: { remove(): void } | null = null; // the body pipe's one-shot accept registration, until opened
  private _bodyPipe: Pipe | null = null; // the body pipe, while the host is pulling from it
  private _started = false;
  private _cancelled = false;

  constructor(
    method: string,
    url: string,
    headers: HeaderEntry[],
    kind: BodyKind,
    body: HttpBody | null,
    len: number,
    opts: RequestOpts | null,
    logCtx: string[] | null,
  ) {
    this._method = method;
    this._url = url;
    this._headers = headers;
    this._kind = kind;
    this._body = body;
    this._len = len;
    this._opts = opts;
    this._logCtx = logCtx;
  }
  // Retire the body plumbing: unregister a never-opened body command, abort an in-flight body
  // transfer. Runs on every terminal path (response consumed, cancel, failure) — a request
  // whose body the server never pulled (cache hit, early error) must not leak the registration.
  private _retireBody(reason: string): void {
    const reg = this._bodyReg;
    if (reg) {
      this._bodyReg = null;
      reg.remove();
    }
    const bp = this._bodyPipe;
    if (bp) {
      this._bodyPipe = null;
      bp.sink.close(reason); // RESET: allowed mid-write; a no-op if the body already ENDed cleanly
      bp.source.close();
    }
  }
  cancel(): void {
    if (this._cancelled) return;
    this._cancelled = true;
    if (this._response) return this._response.cancel(); // its _onFinished retires the body
    this._retireBody("cancelled");
    const io = this._io;
    if (io) {
      io.sink.close("cancelled"); // RESET: allowed mid-write, rejects a parked send
      io.source.close();
    }
  }
  async execute(): Promise<HttpResponse> {
    if (this._started) throw new Error("execute() called twice");
    this._started = true;
    if (this._cancelled) throw new Error("request cancelled");
    const self = this;
    const io = open("http");
    this._io = io;
    // The body rides its own pipe: register a one-shot command the server opens lazily, on the
    // transport's FIRST body pull — a response that never needs the body never transfers it.
    // Raw bytes, END = body complete; the callback kind gets the pipe's real streaming sink.
    // Wire keys QUOTED/bracketed — this head crosses to Kotlin as JSON and must survive Closure's
    // ADVANCED renaming literally.
    const head: RequestHead = {
      "method": this._method,
      "url": this._url,
      "headers": this._headers,
    };
    if (this._kind !== null) {
      const bodyCmd = "http/body/" + io.id;
      head["bodyCmd"] = bodyCmd;
      this._bodyReg = onAccept(bodyCmd, async function (bp) {
        self._bodyReg = null;
        self._bodyPipe = bp;
        let p: MaybePromise;
        if (self._kind === "text") {
          p = bp.sink.writeUtf8(self._body as string);
          if (p) await p;
        } else if (self._kind === "bytes") {
          p = bp.sink.writeBytes(self._body as Uint8Array);
          if (p) await p;
        } else {
          // capability: a sink the callback cannot read or reopen
          await (self._body as (io: PipeSink) => unknown)(bp.sink);
        }
        await bp.sink.close(); // END: body complete
        self._bodyPipe = null;
      });
    }
    if (this._opts) head["opts"] = this._opts;
    // Threads the caller's logging context to the host so a transport failure (request/response
    // body) logs at its birth site with the full trail (HOST_PROTOCOLS "Logging context").
    if (this._logCtx) head["logCtx"] = this._logCtx;
    try {
      // control pipe: the JSON head (self-framing), then NOTHING outbound except a possible -1
      // cancel — the channel stays unobstructed at every stage.
      let p = io.sink.writeObjectAsJson(head);
      if (p) await p;
      p = io.sink.flush();
      if (p) await p;

      // response: JSON head, then the raw body bytes to EOF on the same pipe (bracket access:
      // wire data from the host, not a declared structure).
      const rh = await io.source.readJsonObject();
      const res = new Response(
        (rh["status"] as number) | 0,
        rh["message"] === undefined ? "" : String(rh["message"]),
        rh["url"] === undefined ? this._url : String(rh["url"]),
        (rh["headers"] as HeaderEntry[] | undefined) || [],
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
      const io2 = this._io;
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
