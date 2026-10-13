// base.js's own watch-page /next feeds the Recommended feed: its end screen and related column are
// reported up the status pipe (`watchNext`) for music videos only.
// The /next and /player calls race, so whichever lands second makes the report.

import { stringifyJson, utf8Decode } from "../../transport";
import { addInterceptor, BufferedResponse } from "../../protocol/http/intercept";
import type { HttpChain } from "../../protocol/http/intercept";
import { playerCategory, setPlayerResponseListener } from "./player_res";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function isNextUrl(url: string): boolean {
  const q = url.indexOf("?");
  const path = q >= 0 ? url.slice(0, q) : url;
  return path.length >= 17 && path.slice(path.length - 17) === "/youtubei/v1/next";
}

/** The watched video of a first-page /next; continuations carry no videoId and are skipped. */
function requestVideoId(body: unknown): string {
  if (typeof body !== "string" && !(body instanceof Uint8Array)) return "";
  try {
    const rec = asRec(JSON.parse(typeof body === "string" ? body : utf8Decode(body)));
    const videoId = rec ? rec["videoId"] : undefined;
    return typeof videoId === "string" && rec !== null && rec["continuation"] == null
      ? videoId
      : "";
  } catch (_e) {
    return "";
  }
}

/** End screen first (YouTube's own picks + Mix tiles), then the related column, sections flattened. */
function relatedContents(rec: Record<string, unknown>): unknown[] {
  const endScreen = asRec(
    asRec(asRec(asRec(rec["playerOverlays"])?.["playerOverlayRenderer"])?.["endScreen"])?.[
      "watchNextEndScreenRenderer"
    ],
  )?.["results"];
  const column = asRec(
    asRec(asRec(asRec(rec["contents"])?.["twoColumnWatchNextResults"])?.["secondaryResults"])?.[
      "secondaryResults"
    ],
  )?.["results"];
  const out: unknown[] = [];
  for (const list of [endScreen, column]) {
    if (!Array.isArray(list)) continue;
    for (const item of list) {
      const section = asRec(asRec(item)?.["itemSectionRenderer"])?.["contents"];
      if (Array.isArray(section)) out.push(...section);
      else out.push(item);
    }
  }
  return out;
}

let pending: { videoId: string; contents: string } | null = null;
let reported = "";
let reportHook: ((rec: Record<string, unknown>) => void) | null = null;

/** The status pipe's sink for `watchNext` reports; null while nobody listens. */
export function setWatchNextHook(fn: ((rec: Record<string, unknown>) => void) | null): void {
  reportHook = fn;
  maybeReport();
}

function maybeReport(): void {
  const next = pending;
  if (next === null || reportHook === null || next.videoId === reported) return;
  const category = playerCategory(next.videoId);
  if (category === "") return;
  pending = null;
  reported = next.videoId;
  if (category.toLowerCase().indexOf("music") < 0) return;
  reportHook({ "videoId": next.videoId, "contents": next.contents });
}

async function watchNextIntercept(chain: HttpChain): Promise<HttpResponse> {
  const req = chain.request;
  if (!isNextUrl(req.url)) return chain.proceed();
  const videoId = requestVideoId(req.body);
  const res = await chain.proceed();
  if (videoId === "" || !res.ok) return res;
  const rec = await res.jsonObject();
  try {
    const contents = relatedContents(rec);
    if (contents.length > 0) {
      pending = { videoId: videoId, contents: JSON.stringify(contents) };
      maybeReport();
    }
  } catch (e) {
    console.warn("watch-next: harvest failed:", e);
  }
  return new BufferedResponse(res, stringifyJson(rec));
}

let installed = false;

export function installWatchNextHarvest(): void {
  if (installed) return;
  installed = true;
  setPlayerResponseListener(maybeReport);
  addInterceptor(watchNextIntercept);
}
