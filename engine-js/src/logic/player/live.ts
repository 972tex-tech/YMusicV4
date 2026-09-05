// movie_player.getPlayerResponse() is the playing video (webview-js
// skip_ads.ts isLive, webview_api.ts getCurrentPlayerRes).

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function videoDetails(): Record<string, unknown> | null {
  const el = document.getElementById("movie_player") as unknown as Record<string, unknown> | null;
  const fn = el ? el["getPlayerResponse"] : undefined;
  if (typeof fn !== "function") return null;
  try {
    const rec = asRec(fn.call(el));
    return rec ? asRec(rec["videoDetails"]) : null;
  } catch (_e) {
    return null;
  }
}

function detailsAreLive(vd: Record<string, unknown>): boolean {
  return vd["isLive"] === true || vd["isLiveContent"] === true;
}

export function isYmLive(): boolean {
  const vd = videoDetails();
  return !!vd && detailsAreLive(vd);
}

export function isKnownVod(): boolean {
  const vd = videoDetails();
  return !!vd && !detailsAreLive(vd);
}
