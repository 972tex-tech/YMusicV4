/** Adaptive 144p itags (Qf tiny). Fake in /player; strip from SABR vid=/br/sel. */
export const ITAG_144P = [160, 278, 330, 394, 598, 694];

/** Adaptive video itags by codec. Used to read SABR sel= and pick a matching 144p. */
export const ITAG_AV1 = [
  394, 395, 396, 397, 398, 399, 400, 401, 402, 571, 694, 695, 696, 697, 698, 699, 700,
];
export const ITAG_AVC = [160, 133, 134, 135, 136, 137, 138, 264, 266, 298, 299, 598];
export const ITAG_VP9 = [
  278, 242, 243, 244, 247, 248, 271, 272, 302, 303, 308, 313, 315, 330, 331, 332, 333, 334, 335,
  336, 337,
];

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

function inSet(set: number[], itag: number): boolean {
  return set.indexOf(itag) >= 0;
}

function codecOf(itag: number): string {
  if (inSet(ITAG_AV1, itag)) return "av1";
  if (inSet(ITAG_AVC, itag)) return "avc";
  if (inSet(ITAG_VP9, itag)) return "vp9";
  return "";
}

export function fake144ByItag(itag: number): Fake144Row | null {
  for (let i = 0; i < lastRows.length; i++) {
    if (lastRows[i]!.itag === itag) return lastRows[i]!;
  }
  return null;
}

function rowWithItag(prefer: number[]): Fake144Row | null {
  for (let i = 0; i < prefer.length; i++) {
    const row = fake144ByItag(prefer[i]!);
    if (row) return row;
  }
  return null;
}

/** sel= initialized itags. No video sel → av1/avc (mp4), not vp9. */
export function pickFake144(selItags: number[]): Fake144Row | null {
  if (!lastRows.length) return null;
  let codec = "";
  for (let i = 0; i < selItags.length; i++) {
    const c = codecOf(selItags[i]!);
    if (c) {
      codec = c;
      break;
    }
  }
  if (!codec) codec = "av1";
  const prefer = codec === "vp9" ? [278, 330] : codec === "avc" ? [160, 598] : [394, 694];
  return rowWithItag(prefer) || rowWithItag([394, 160, 598, 694, 278, 330]) || lastRows[0]!;
}
