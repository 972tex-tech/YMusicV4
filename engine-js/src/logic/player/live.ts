// Same source as webview-js: movie_player.getPlayerResponse().videoDetails
// (skip_ads.ts isLive, webview_api.ts getCurrentPlayerRes).

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function playerResponse(): Record<string, unknown> | null {
  const el = document.getElementById("movie_player") as unknown as Record<string, unknown> | null;
  const fn = el ? el["getPlayerResponse"] : undefined;
  if (typeof fn !== "function") return null;
  try {
    return asRec(fn.call(el));
  } catch (_e) {
    return null;
  }
}

function videoDetails(): Record<string, unknown> | null {
  const rec = playerResponse();
  return rec ? asRec(rec["videoDetails"]) : null;
}

function watchVideoId(): string {
  const s = String(location.search || "");
  const q = s.charAt(0) === "?" ? s.slice(1) : s;
  const parts = q.split("&");
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    if (p.indexOf("v=") === 0) return p.slice(2);
  }
  return "";
}

function detailsAreLive(vd: Record<string, unknown>): boolean {
  return vd["isLive"] === true || vd["isLiveContent"] === true;
}

export function isYmLive(): boolean {
  const vd = videoDetails();
  return !!vd && detailsAreLive(vd);
}

/** getPlayerResponse is for the watch URL and is not a live broadcast. */
export function isKnownVod(): boolean {
  const vd = videoDetails();
  if (!vd) return false;
  const id = vd["videoId"] != null ? String(vd["videoId"]) : "";
  const watch = watchVideoId();
  if (watch && id && id !== watch) return false;
  return !detailsAreLive(vd);
}
