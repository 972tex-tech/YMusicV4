/** Adaptive 144p itags (Qf tiny). Fake in /player; strip from SABR vid=/br/sel. */
export const ITAG_144P = [160, 278, 330, 394, 598, 694];

export function is144pItag(itag: number): boolean {
  return ITAG_144P.indexOf(itag) >= 0;
}

export type Fake144Row = {
  itag: number;
  mime: string;
  lmt: string;
  initStart: number;
  initEnd: number;
  indexStart: number;
  indexEnd: number;
};

let lastVideoId = "";
let lastRows: Fake144Row[] = [];

export function recordFake144(videoId: string, rows: Fake144Row[]): void {
  lastVideoId = videoId;
  lastRows = rows;
}

export function fake144VideoId(): string {
  return lastVideoId;
}

function mimeContainer(mime: string): string {
  const m = String(mime).toLowerCase();
  if (m.indexOf("video/mp4") === 0) return "mp4";
  if (m.indexOf("video/webm") === 0) return "webm";
  return "";
}

/** Match the video SourceBuffer container. No SB leftover → mp4, not vp9/webm. */
export function pickFake144(sbMime?: string): Fake144Row | null {
  if (!lastRows.length) return null;
  const have = mimeContainer(sbMime || "");
  const want = have === "webm" ? "webm" : "mp4";
  const pool: Fake144Row[] = [];
  for (let i = 0; i < lastRows.length; i++) {
    if (mimeContainer(lastRows[i]!.mime) === want) pool.push(lastRows[i]!);
  }
  const use = pool.length ? pool : lastRows;
  const prefer = want === "webm" ? [278, 330] : [394, 160, 598, 694];
  for (let i = 0; i < prefer.length; i++) {
    const itag = prefer[i]!;
    for (let j = 0; j < use.length; j++) {
      if (use[j]!.itag === itag) return use[j]!;
    }
  }
  return use[0]!;
}
