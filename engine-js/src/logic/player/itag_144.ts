/** Adaptive 144p itags (Qf tiny). Fake in /player; strip from SABR vid= and br. */
export const ITAG_144P = [160, 278, 330, 394, 598, 694];

export function is144pItag(itag: number): boolean {
  return ITAG_144P.indexOf(itag) >= 0;
}
