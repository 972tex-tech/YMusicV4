// Host opens listen/player/status or listen/player/catalog to subscribe.
// Status is event-driven (webview-js): each message is only the group that changed.
//   progress — onVideoProgress: position, buffer, duration, seek window
//   state    — onStateChange: newState, playWhenReady, live, error extras
//   picture  — onVideoDataChange: videoWidth, videoHeight

import { JsEngineErrorImpl } from "../../protocol/command";
import {
  catalogPlayer,
  ListenKind,
  picturePlayer,
  playerStatePlayer,
  progressClockPlayer,
  setCatalogHook,
  setListenHook,
} from "./boot";

let progressPipe: Pipe | null = null;
let catalogPipe: Pipe | null = null;

async function emit(
  sink: PipeSink,
  rec: Record<string, unknown>,
): Promise<void> {
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
  return picturePlayer();
}

export async function listenPlayerStatus(pipe: Pipe): Promise<void> {
  if (progressPipe) throw new JsEngineErrorImpl("listen/player/status busy", "E20089");
  progressPipe = pipe;
  const last: Record<ListenKind, string> = { progress: "", state: "", picture: "" };

  function push(kinds: ListenKind[]): void {
    const rec: Record<string, unknown> = {};
    for (let i = 0; i < kinds.length; i++) {
      const kind = kinds[i]!;
      const body = snapshot(kind);
      const js = JSON.stringify(body);
      if (js === last[kind]) continue;
      last[kind] = js;
      rec[kind] = body;
    }
    if (!Object.keys(rec).length) return;
    const sink = pipe.sink;
    void emit(sink, rec);
  }

  try {
    setListenHook(function (kind: ListenKind) {
      push([kind]);
    });
    push(["state", "progress", "picture"]);
    await waitHostClose(pipe);
  } finally {
    setListenHook(null);
    progressPipe = null;
  }
}

export async function runCatalogSession(pipe: Pipe): Promise<void> {
  if (catalogPipe) throw new JsEngineErrorImpl("listen/player/catalog busy", "E20090");
  catalogPipe = pipe;
  const last = { json: "" };
  const push = function (): void {
    const rec = catalogPlayer();
    const js = JSON.stringify(rec);
    if (js === last.json) return;
    last.json = js;
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
