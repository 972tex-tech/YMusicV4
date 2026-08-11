/**
 * Minimal WebM muxer for the audio-only blank video track: an init segment plus clusters each
 * holding one 16x16 black VP9 keyframe in a BlockGroup with an explicit BlockDuration, so MSE
 * reports continuous buffered ranges. ~34 bytes per 10s cluster, generated locally — the whole
 * point is that audio-only playback appends these instead of fetching video from the network.
 */

// 16x16 black VP9 keyframe (ffmpeg libvpx-vp9), 28 bytes.
const BLANK_VP9_FRAME = [
  0x82, 0x49, 0x83, 0x42, 0x00, 0x00, 0xf0, 0x00, 0xf6, 0x00, 0x38, 0x24, 0x1c, 0x18,
  0x4a, 0x00, 0x00, 0x30, 0x60, 0x00, 0x00, 0x10, 0xbf, 0xff, 0xfd, 0x48, 0x8c, 0x00,
];

export const BLANK_VIDEO_MIME = 'video/webm; codecs="vp9"';

function vint(size: number): number[] {
  if (size < 0x7f) return [0x80 | size];
  if (size < 0x3fff) return [0x40 | (size >> 8), size & 0xff];
  if (size < 0x1fffff) return [0x20 | (size >> 16), (size >> 8) & 0xff, size & 0xff];
  return [0x10 | (size >>> 24), (size >> 16) & 0xff, (size >> 8) & 0xff, size & 0xff];
}

function el(id: number[], payload: number[]): number[] {
  return id.concat(vint(payload.length), payload);
}

function uintEl(id: number[], val: number): number[] {
  let n = 1;
  let tmp = val;
  while (tmp >= 256) {
    tmp = Math.floor(tmp / 256);
    n++;
  }
  const payload: number[] = [];
  for (let i = n - 1; i >= 0; i--) payload.push(Math.floor(val / Math.pow(256, i)) % 256);
  return el(id, payload);
}

function floatEl(id: number[], val: number): number[] {
  const dv = new DataView(new ArrayBuffer(8));
  dv.setFloat64(0, val);
  const payload: number[] = [];
  for (let i = 0; i < 8; i++) payload.push(dv.getUint8(i));
  return el(id, payload);
}

function strEl(id: number[], s: string): number[] {
  const payload: number[] = [];
  for (let i = 0; i < s.length; i++) payload.push(s.charCodeAt(i));
  return el(id, payload);
}

/** Init segment: EBML header + Segment(unknown size) + Info(1ms timescale) + one VP9 track. */
export function buildBlankInit(durationMs?: number): Uint8Array {
  const ebml = el([0x1a, 0x45, 0xdf, 0xa3], ([] as number[]).concat(
    uintEl([0x42, 0x86], 1), // EBMLVersion
    uintEl([0x42, 0xf7], 1), // EBMLReadVersion
    uintEl([0x42, 0xf2], 4), // EBMLMaxIDLength
    uintEl([0x42, 0xf3], 8), // EBMLMaxSizeLength
    strEl([0x42, 0x82], 'webm'),
    uintEl([0x42, 0x87], 4), // DocTypeVersion
    uintEl([0x42, 0x85], 2), // DocTypeReadVersion
  ));
  const info = el([0x15, 0x49, 0xa9, 0x66], ([] as number[]).concat(
    uintEl([0x2a, 0xd7, 0xb1], 1000000), // TimecodeScale: 1ms units
    durationMs && isFinite(durationMs) ? floatEl([0x44, 0x89], durationMs) : [],
    strEl([0x4d, 0x80], 'ymblank'),
    strEl([0x57, 0x41], 'ymblank'),
  ));
  const video = el([0xe0], ([] as number[]).concat(
    uintEl([0xb0], 16), // PixelWidth
    uintEl([0xba], 16), // PixelHeight
  ));
  const track = el([0xae], ([] as number[]).concat(
    uintEl([0xd7], 1), // TrackNumber
    uintEl([0x73, 0xc5], 1), // TrackUID
    uintEl([0x83], 1), // TrackType: video
    uintEl([0x9c], 0), // FlagLacing
    strEl([0x86], 'V_VP9'),
    video,
  ));
  const tracks = el([0x16, 0x54, 0xae, 0x6b], track);
  const segment = [0x18, 0x53, 0x80, 0x67, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]
    .concat(info, tracks);
  return new Uint8Array(ebml.concat(segment));
}

/** One cluster at tMs: BlockGroup{one keyframe, BlockDuration=durMs} — buffered [tMs, tMs+durMs). */
function buildCluster(tMs: number, durMs: number): number[] {
  const blockPayload = [0x81, 0x00, 0x00, 0x00].concat(BLANK_VP9_FRAME); // track 1, rel t 0, flags 0
  const blockGroup = el([0xa0], ([] as number[]).concat(
    el([0xa1], blockPayload),
    uintEl([0x9b], durMs),
  ));
  return el([0x1f, 0x43, 0xb6, 0x75], ([] as number[]).concat(uintEl([0xe7], tMs), blockGroup));
}

/** Blank media covering [fromMs, toMs). */
export function buildBlankClusters(fromMs: number, toMs: number, clusterMs = 10000): Uint8Array {
  const out: number[] = [];
  for (let t = Math.max(0, Math.floor(fromMs)); t < toMs; t += clusterMs) {
    const dur = Math.min(clusterMs, Math.ceil(toMs - t));
    out.push.apply(out, buildCluster(Math.round(t), dur));
  }
  return new Uint8Array(out);
}
