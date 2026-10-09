// Host opens listen/player/status or listen/player/catalog to subscribe.
// Status is event-driven (webview-js): each message is only the group that changed.
//   progress — onVideoProgress: position, buffer, duration, seek window
//   state    — onStateChange: newState, playWhenReady, live, error extras
//   picture  — onVideoDataChange: videoWidth, videoHeight
//   details  — onVideoDataChange: videoId, channelTitle, channelUrl
//   watchNext — a music video's related items: videoId, contents (JSON text; see watch_next.ts)

import { JsEngineErrorImpl } from "../../protocol/command";
import {
  catalogPlayer,
  catalogReady,
  detailsPlayer,
  ListenKind,
  picturePlayer,
  playerStatePlayer,
  progressClockPlayer,
  setCatalogHook,
  setListenHook,
} from "./boot";
import { setWatchNextHook } from "./watch_next";

let progressPipe: Pipe | null = null;
let catalogPipe: Pipe | null = null;

async function emit(sink: PipeSink, rec: Record<string, unknown>): Promise<void> {
  const p = sink.writeObjectAsJson(rec);
  if (p) await p;
  const f = sink.flush();
  if (f) await f;
}

async function waitHostClose(pipe: Pipe): Promise<void> {
  try {
    const r = pipe.source.readJsonObject();
    if (r && typeof (r as Promise<unknown>).then === "function") await r;
  } catch (_e) {
    /* host closed */
  }
}

function snapshot(kind: ListenKind): Record<string, unknown> {
  if (kind === "progress") return progressClockPlayer();
  if (kind === "state") return playerStatePlayer();
  if (kind === "details") return detailsPlayer();
  return picturePlayer();
}

function sameProgress(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["progress"] === b["progress"] &&
    a["bufferedMs"] === b["bufferedMs"] &&
    a["duration"] === b["duration"] &&
    a["live"] === b["live"] &&
    a["seekable"] === b["seekable"] &&
    a["seekableStartMs"] === b["seekableStartMs"] &&
    a["seekableEndMs"] === b["seekableEndMs"] &&
    a["progressLabel"] === b["progressLabel"] &&
    a["videoId"] === b["videoId"]
  );
}

function sameState(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["newState"] === b["newState"] &&
    a["playWhenReady"] === b["playWhenReady"] &&
    a["live"] === b["live"] &&
    a["code"] === b["code"] &&
    a["message"] === b["message"]
  );
}

function samePicture(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return a["videoWidth"] === b["videoWidth"] && a["videoHeight"] === b["videoHeight"];
}

function sameDetails(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["videoId"] === b["videoId"] &&
    a["channelTitle"] === b["channelTitle"] &&
    a["channelUrl"] === b["channelUrl"]
  );
}

function sameCatalog(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  if (
    a["quality"] !== b["quality"] ||
    a["preferQuality"] !== b["preferQuality"] ||
    a["audioOnly"] !== b["audioOnly"] ||
    a["caption"] !== b["caption"]
  )
    return false;
  const qa = a["qualities"];
  const qb = b["qualities"];
  if (!Array.isArray(qa) || !Array.isArray(qb) || qa.length !== qb.length) return false;
  for (let i = 0; i < qa.length; i++) {
    const x = qa[i] as Record<string, unknown>;
    const y = qb[i] as Record<string, unknown>;
    if (x["quality"] !== y["quality"] || x["label"] !== y["label"]) return false;
  }
  const ca = a["captionTracks"];
  const cb = b["captionTracks"];
  if (!Array.isArray(ca) || !Array.isArray(cb) || ca.length !== cb.length) return false;
  for (let i = 0; i < ca.length; i++) {
    const x = ca[i] as Record<string, unknown>;
    const y = cb[i] as Record<string, unknown>;
    if (x["vssId"] !== y["vssId"] || x["displayName"] !== y["displayName"]) return false;
  }
  return true;
}

function sameListen(
  kind: ListenKind,
  a: Record<string, unknown>,
  b: Record<string, unknown>,
): boolean {
  if (kind === "progress") return sameProgress(a, b);
  if (kind === "state") return sameState(a, b);
  if (kind === "details") return sameDetails(a, b);
  return samePicture(a, b);
}

export async function listenPlayerStatus(pipe: Pipe): Promise<void> {
  if (progressPipe) throw new JsEngineErrorImpl("listen/player/status busy", "E20089");
  progressPipe = pipe;
  const last: Partial<Record<ListenKind, Record<string, unknown>>> = {};

  function push(kinds: ListenKind[]): void {
    const rec: Record<string, unknown> = {};
    for (let i = 0; i < kinds.length; i++) {
      const kind = kinds[i]!;
      const body = snapshot(kind);
      const prev = last[kind];
      if (prev && sameListen(kind, prev, body)) continue;
      last[kind] = body;
      rec[kind] = body;
    }
    if (!Object.keys(rec).length) return;
    const sink = pipe.sink;
    void emit(sink, rec);
  }

  try {
    setListenHook(function (kind: ListenKind) {
      // The player response can land after the first onVideoDataChange; a state change re-checks.
      push(kind === "state" ? ["state", "details"] : [kind]);
    });
    push(["state", "progress", "picture", "details"]);
    setWatchNextHook(function (watchNext: Record<string, unknown>) {
      void emit(pipe.sink, { "watchNext": watchNext });
    });
    await waitHostClose(pipe);
  } finally {
    setWatchNextHook(null);
    setListenHook(null);
    progressPipe = null;
  }
}

export async function runCatalogSession(pipe: Pipe): Promise<void> {
  if (catalogPipe) throw new JsEngineErrorImpl("listen/player/catalog busy", "E20090");
  catalogPipe = pipe;
  let last: Record<string, unknown> | null = null;
  const push = function (): void {
    if (!catalogReady()) return;
    const rec = catalogPlayer();
    if (last && sameCatalog(last, rec)) return;
    last = rec;
    void emit(pipe.sink, rec);
  };
  setCatalogHook(push);
  try {
    push();
    await waitHostClose(pipe);
  } finally {
    setCatalogHook(null);
    catalogPipe = null;
  }
}
