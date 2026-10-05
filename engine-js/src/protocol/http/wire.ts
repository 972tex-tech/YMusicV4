// The control-pipe wire shapes (v3, HOST_PROTOCOLS §1; server side: HttpProtocol.kt): a CONTROL
// pipe carries the JSON heads (self-framing — readJsonObject/JsonReader stop at the value's end)
// and the response body as raw bytes to EOF; a request body rides its OWN pipe — a one-shot command
// named in the head ("bodyCmd") that the server opens lazily on the transport's first pull, raw
// bytes, END = done. Headers cross as [[name, value], …], so head injection is impossible by
// construction. The control pipe's outbound side carries NOTHING after the head: END = done,
// RESET = cancel — cancellation is observable at every stage.

import type { LogWire } from "../logger";

export type BodyKind = "text" | "bytes" | "callback" | null;

export interface RequestOpts {
  timeoutMs?: number;
  followRedirects?: boolean;
}

/** The JSON head sent down the control pipe (server side: HttpProtocol's readObject). */
export interface RequestHead {
  method: string;
  url: string;
  headers: HeaderEntry[];
  bodyCmd?: string;
  opts?: RequestOpts;
  log?: LogWire;
}
