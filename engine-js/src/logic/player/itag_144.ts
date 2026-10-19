/** Adaptive 144p itags (Qf tiny). Used as audio-only fake FormatId (itag+lmt). */
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

export function isVideoItag(itag: number): boolean {
  return (
    is144pItag(itag) || inSet(ITAG_AV1, itag) || inSet(ITAG_AVC, itag) || inSet(ITAG_VP9, itag)
  );
}

export type Fake144Row = {
  itag: number;
  mime: string;
  lmt: string;
  /** Inclusive byte indexes from /player (YouTube 0–699 = 700 bytes). */
  initStart: number;
  initEnd: number;
  indexStart: number;
  indexEnd: number;
  bitrate: number;
  /** FORMAT_INIT 10 / TimeRange 3. mp4 video dump uses 15360, webm 1000. */
  timescale: number;
};

type Fake144Video = { durationMs: number; live: boolean; rows: Fake144Row[] };

const byVideo: Record<string, Fake144Video> = {};

export function recordFake144(
  videoId: string,
  rows: Fake144Row[],
  durationMs: number,
  live: boolean,
): void {
  if (!videoId || !rows.length) return;
  byVideo[videoId] = {
    durationMs: durationMs > 0 ? durationMs : 0,
    live: live,
    rows: rows,
  };
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

function rowWithItag(rows: Fake144Row[], prefer: number[]): Fake144Row | null {
  for (let i = 0; i < prefer.length; i++) {
    const want = prefer[i]!;
    for (let j = 0; j < rows.length; j++) {
      if (rows[j]!.itag === want) return rows[j]!;
    }
  }
  return null;
}

function decodable(codec: string): boolean {
  if (codec === "av1") return typeof __mseAv1Ok !== "function" || !!__mseAv1Ok();
  if (codec === "vp9") return typeof __mseVp9Ok !== "function" || !!__mseVp9Ok();
  return codec !== "";
}

function firstDecodableCodec(itags: number[]): string {
  for (let i = 0; i < itags.length; i++) {
    const c = codecOf(itags[i]!);
    if (decodable(c)) return c;
  }
  return "";
}

/** Codec: sel= video, else playable video ladder, else best decodable. Match its 144p (vp9→278). */
export function pickFake144(
  videoId: string,
  selItags: number[],
  playableVideoItags: number[],
): { row: Fake144Row; durationMs: number; live: boolean } | null {
  const rec = byVideo[videoId];
  if (!rec || !rec.rows.length) return null;
  const rows = rec.rows;
  const codec =
    firstDecodableCodec(selItags) ||
    firstDecodableCodec(playableVideoItags) ||
    (decodable("av1") ? "av1" : decodable("vp9") ? "vp9" : "avc");
  const prefer = codec === "vp9" ? [278, 330] : codec === "avc" ? [160, 598] : [394, 694];
  const fallback = [394, 160, 598, 694, 278, 330].filter(function (itag) {
    return decodable(codecOf(itag));
  });
  const row = rowWithItag(rows, prefer) || rowWithItag(rows, fallback) || rows[0]!;
  return { row: row, durationMs: rec.durationMs, live: rec.live };
}
