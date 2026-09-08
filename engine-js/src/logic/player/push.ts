// Host opens listen/player/status or listen/player/catalog to subscribe (same as player/caption).

import { JsEngineErrorImpl } from "../../protocol/command";
import { activeHtmlMedia } from "../../protocol/mse";
import { catalogPlayer, progressPlayer, setCatalogHook, setProgressHook } from "./boot";

let progressPipe: Pipe | null = null;
let catalogPipe: Pipe | null = null;

async function emit(
  sink: PipeSink,
  rec: Record<string, unknown>,
  last: { json: string },
): Promise<void> {
  const js = JSON.stringify(rec);
  if (js === last.json) return;
  last.json = js;
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

export async function runProgressSession(pipe: Pipe): Promise<void> {
  if (progressPipe) throw new JsEngineErrorImpl("listen/player/status busy", "E20089");
  progressPipe = pipe;
  const last = { json: "" };
  const onEvt = function (): void {
    const sink = pipe.sink;
    void emit(sink, progressPlayer(), last);
  };
  const v = activeHtmlMedia();
  /* Clock lives in C. HTML timeupdate only runs while mediaTick is on
     (HTMLMediaElement.play). YouTube can leave that off after load/reset
     while DAC still plays — poll so the seek bar does not freeze. */
  const tick = setInterval(onEvt, 250);
  try {
    if (v) {
      v["addEventListener"]("timeupdate", onEvt);
      v["addEventListener"]("play", onEvt);
      v["addEventListener"]("pause", onEvt);
      v["addEventListener"]("seeked", onEvt);
      v["addEventListener"]("ended", onEvt);
      v["addEventListener"]("durationchange", onEvt);
    }
    setProgressHook(onEvt);
    await emit(pipe.sink, progressPlayer(), last);
    await waitHostClose(pipe);
  } finally {
    clearInterval(tick);
    setProgressHook(null);
    if (v) {
      v["removeEventListener"]("timeupdate", onEvt);
      v["removeEventListener"]("play", onEvt);
      v["removeEventListener"]("pause", onEvt);
      v["removeEventListener"]("seeked", onEvt);
      v["removeEventListener"]("ended", onEvt);
      v["removeEventListener"]("durationchange", onEvt);
    }
    progressPipe = null;
  }
}

export async function runCatalogSession(pipe: Pipe): Promise<void> {
  if (catalogPipe) throw new JsEngineErrorImpl("listen/player/catalog busy", "E20090");
  catalogPipe = pipe;
  const last = { json: "" };
  const push = function (): void {
    void emit(pipe.sink, catalogPlayer(), last);
  };
  setCatalogHook(push);
  try {
    await emit(pipe.sink, catalogPlayer(), last);
    await waitHostClose(pipe);
  } finally {
    setCatalogHook(null);
    catalogPipe = null;
  }
}
