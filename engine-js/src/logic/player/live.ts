// Live bit from /player. Do not infer live from duration or currentTime —
// a 7h DVR window and a 2h VOD look the same, and ct>60 made the first
// minute of live behave as VOD.

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

let live = false;
let havePlayer = false;

export function ingestPlayerLive(rec: Record<string, unknown>): void {
  havePlayer = true;
  live = false;
  const vd = asRec(rec["videoDetails"]);
  if (vd && vd["isLive"] === true) {
    live = true;
    return;
  }
  const ps = asRec(rec["playabilityStatus"]);
  const ls = ps ? ps["liveStreamability"] : undefined;
  if (ls != null && typeof ls === "object") live = true;
}

export function clearPlayerLive(): void {
  havePlayer = false;
  live = false;
}

export function isYmLive(): boolean {
  return live;
}

/** /player said this is not a live broadcast. Unknown until ingest is not VOD. */
export function isKnownVod(): boolean {
  return havePlayer && !live;
}
