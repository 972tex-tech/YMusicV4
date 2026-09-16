// movie_player.getPlayerResponse() is the playing video (webview-js
// skip_ads.ts isLive, webview_api.ts getCurrentPlayerRes).
//
// isLiveContent stays true after the broadcast ends (post-live VOD,
// e.g. 0_LTL7O1xX0). Only videoDetails.isLive is on-air. Ended streams
// are VOD: pin tiny + rest decoder.

import { moviePlayer } from "./movie_player";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function videoDetails(): Record<string, unknown> | null {
  const el = moviePlayer();
  const fn = el ? el["getPlayerResponse"] : undefined;
  if (typeof fn !== "function") return null;
  try {
    const rec = asRec(fn.call(el));
    return rec ? asRec(rec["videoDetails"]) : null;
  } catch (_e) {
    return null;
  }
}

export function isYmLive(): boolean {
  const vd = videoDetails();
  return !!vd && vd["isLive"] === true;
}

export function isKnownVod(): boolean {
  const vd = videoDetails();
  return !!vd && vd["isLive"] !== true;
}
