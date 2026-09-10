/** Adaptive 144p itags (Qf tiny). Fake in /player; strip from SABR vid=/br/sel. */
export const ITAG_144P = [160, 278, 330, 394, 598, 694];

/** lastModified stamped on rewritten 144p rows; FormatId lmt on fake UMP. */
export const FAKE_LMT = "1770737429196304";
export const FAKE_LMT_NUM = 1770737429196304;

export function is144pItag(itag: number): boolean {
  return ITAG_144P.indexOf(itag) >= 0;
}

export type Fake144Row = { itag: number; mime: string };

let lastVideoId = "";
let lastRows: Fake144Row[] = [];

export function recordFake144(videoId: string, rows: Fake144Row[]): void {
  lastVideoId = videoId;
  lastRows = rows;
}

export function fake144VideoId(): string {
  return lastVideoId;
}

export function pickFake144(): Fake144Row | null {
  if (!lastRows.length) return null;
  const prefer = [278, 394, 160];
  for (let i = 0; i < prefer.length; i++) {
    const want = prefer[i]!;
    for (let j = 0; j < lastRows.length; j++) {
      if (lastRows[j]!.itag === want) return lastRows[j]!;
    }
  }
  return lastRows[0]!;
}
