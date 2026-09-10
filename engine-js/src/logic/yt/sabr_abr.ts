// SABR VideoPlaybackAbrRequest: drop fake 144p from playable video (17),
// initialized formats (2), and buffered_ranges (3). VOD product-zero also
// sets ClientAbrState field 40=1 and prepends a fake 144p UMP (FORMAT_INIT
// if sel lacks the itag, then a 1-byte media part covering playerTime).

import { isProductZero } from "../player/audio_only";
import { isYmLive } from "../player/live";
import {
  FAKE_LMT_NUM,
  fake144VideoId,
  is144pItag,
  pickFake144,
} from "../player/itag_144";

function asU8(body: unknown): Uint8Array | null {
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (ArrayBuffer.isView(body))
    return new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  return null;
}

function readVarint(buf: Uint8Array, i: number): { v: number; i: number } | null {
  let v = 0;
  let s = 0;
  while (i < buf.length) {
    const b = buf[i++]!;
    v += (b & 0x7f) * Math.pow(2, s);
    if ((b & 0x80) === 0) return { v: v, i: i };
    s += 7;
    if (s > 56) return null;
  }
  return null;
}

function writeVarint(v: number): number[] {
  const out: number[] = [];
  v = Math.floor(v);
  if (v < 0) v = 0;
  while (v >= 0x80) {
    out.push((v & 0x7f) | 0x80);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
}

type Field = { n: number; w: number; v: number | Uint8Array };

function readFields(buf: Uint8Array): Field[] | null {
  const out: Field[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const n = k.v >>> 3;
    const w = k.v & 7;
    if (w === 0) {
      const x = readVarint(buf, i);
      if (!x) return null;
      i = x.i;
      out.push({ n: n, w: 0, v: x.v });
    } else if (w === 2) {
      const x = readVarint(buf, i);
      if (!x) return null;
      i = x.i;
      if (x.v < 0 || i + x.v > buf.length) return null;
      out.push({ n: n, w: 2, v: buf.subarray(i, i + x.v) });
      i += x.v;
    } else if (w === 1) {
      if (i + 8 > buf.length) return null;
      out.push({ n: n, w: 1, v: buf.subarray(i, i + 8) });
      i += 8;
    } else if (w === 5) {
      if (i + 4 > buf.length) return null;
      out.push({ n: n, w: 5, v: buf.subarray(i, i + 4) });
      i += 4;
    } else return null;
  }
  return out;
}

function writeFields(fs: Field[]): Uint8Array {
  const bytes: number[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    const key = writeVarint((f.n << 3) | f.w);
    for (let j = 0; j < key.length; j++) bytes.push(key[j]!);
    if (f.w === 0) {
      const v = writeVarint(f.v as number);
      for (let j = 0; j < v.length; j++) bytes.push(v[j]!);
    } else {
      const u = f.v as Uint8Array;
      if (f.w === 2) {
        const ln = writeVarint(u.length);
        for (let j = 0; j < ln.length; j++) bytes.push(ln[j]!);
      }
      for (let j = 0; j < u.length; j++) bytes.push(u[j]!);
    }
  }
  return new Uint8Array(bytes);
}

function formatItag(msg: Uint8Array): number | null {
  const fs = readFields(msg);
  if (!fs) return null;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 1 && f.w === 0) return f.v as number;
  }
  return null;
}

function rangeItag(msg: Uint8Array): number | null {
  const fs = readFields(msg);
  if (!fs) return null;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 1 && f.w === 2 && f.v instanceof Uint8Array) return formatItag(f.v);
  }
  return null;
}

function rangeWindow(msg: Uint8Array): { start: number; dur: number } | null {
  const fs = readFields(msg);
  if (!fs) return null;
  let start: number | null = null;
  let dur: number | null = null;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 2 && f.w === 0) start = f.v as number;
    if (f.n === 3 && f.w === 0) dur = f.v as number;
  }
  if (start == null || dur == null) return null;
  return { start: start, dur: dur };
}

function asciiU8(s: string): Uint8Array {
  const u = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i) & 0xff;
  return u;
}

function concatU8(parts: Uint8Array[]): Uint8Array {
  let n = 0;
  for (let i = 0; i < parts.length; i++) n += parts[i]!.length;
  const out = new Uint8Array(n);
  let off = 0;
  for (let i = 0; i < parts.length; i++) {
    out.set(parts[i]!, off);
    off += parts[i]!.length;
  }
  return out;
}

/** UMP varint (not protobuf). Same widths as yt-media Ump.kt. */
function writeUmpVarint(v: number): number[] {
  v = Math.floor(v);
  if (v < 0) v = 0;
  if (v < 128) return [v];
  if (v < 16384) return [(v & 0x3f) | 0x80, (v >>> 6) & 0xff];
  if (v < 2097152)
    return [(v & 0x1f) | 0xc0, (v >>> 5) & 0xff, (v >>> 13) & 0xff];
  if (v < 268435456)
    return [(v & 0x0f) | 0xe0, (v >>> 4) & 0xff, (v >>> 12) & 0xff, (v >>> 20) & 0xff];
  return [0xf0, v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff];
}

function umpPart(id: number, payload: Uint8Array): Uint8Array {
  const hid = writeUmpVarint(id);
  const sz = writeUmpVarint(payload.length);
  const out = new Uint8Array(hid.length + sz.length + payload.length);
  out.set(hid, 0);
  out.set(sz, hid.length);
  out.set(payload, hid.length + sz.length);
  return out;
}

function encodeFormatId(itag: number, lmt: number): Uint8Array {
  return writeFields([
    { n: 1, w: 0, v: itag },
    { n: 2, w: 0, v: lmt },
  ]);
}

const INIT_HID = 0xf0;
const MEDIA_HID = 0xf1;
/** initRange 0–1 inclusive = 2 bytes. */
const INIT_BYTES = new Uint8Array([0, 0]);
const MEDIA_BYTES = new Uint8Array([0]);

function formatInitUmp(
  videoId: string,
  itag: number,
  lmt: number,
  mime: string,
  durationMs: number,
): Uint8Array {
  const fid = encodeFormatId(itag, lmt);
  const fs: Field[] = [
    { n: 1, w: 2, v: asciiU8(videoId) },
    { n: 2, w: 2, v: fid },
  ];
  if (durationMs > 0) fs.push({ n: 3, w: 0, v: durationMs });
  fs.push({ n: 5, w: 2, v: asciiU8(mime) });
  fs.push({ n: 10, w: 0, v: 1000 });
  return umpPart(42, writeFields(fs));
}

function mediaHeaderUmp(opts: {
  hid: number;
  videoId: string;
  itag: number;
  lmt: number;
  init: boolean;
  seq: number;
  startMs: number;
  durationMs: number;
  startRange: number;
  clen: number;
}): Uint8Array {
  const fid = encodeFormatId(opts.itag, opts.lmt);
  const fs: Field[] = [
    { n: 1, w: 0, v: opts.hid },
    { n: 2, w: 2, v: asciiU8(opts.videoId) },
    { n: 3, w: 0, v: opts.itag },
    { n: 4, w: 0, v: opts.lmt },
    { n: 6, w: 0, v: opts.startRange },
    { n: 8, w: 0, v: opts.init ? 1 : 0 },
    { n: 13, w: 2, v: fid },
    { n: 14, w: 0, v: opts.clen },
  ];
  if (!opts.init) {
    fs.push({ n: 9, w: 0, v: opts.seq });
    fs.push({ n: 11, w: 0, v: opts.startMs });
    fs.push({ n: 12, w: 0, v: opts.durationMs });
    fs.push({
      n: 15,
      w: 2,
      v: writeFields([
        { n: 1, w: 0, v: opts.startMs },
        { n: 2, w: 0, v: opts.durationMs },
        { n: 3, w: 0, v: 1000 },
      ]),
    });
  }
  return umpPart(20, writeFields(fs));
}

function mediaDataUmp(hid: number, data: Uint8Array): Uint8Array {
  const p = new Uint8Array(1 + data.length);
  p[0] = hid;
  p.set(data, 1);
  return umpPart(21, p);
}

function mediaEndUmp(hid: number): Uint8Array {
  return umpPart(22, new Uint8Array([hid]));
}

function isAudioItag(t: number): boolean {
  return (
    t === 139 ||
    t === 140 ||
    t === 141 ||
    t === 249 ||
    t === 250 ||
    t === 251 ||
    t === 599 ||
    t === 600 ||
    t === 774
  );
}

function vodAudioOnly(): boolean {
  return isProductZero() && !isYmLive();
}

function rewriteCas40(cas: Uint8Array): Uint8Array {
  const fs = readFields(cas);
  if (!fs) return cas;
  const out: Field[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 40) continue;
    out.push(f);
  }
  out.push({ n: 40, w: 0, v: 1 });
  return writeFields(out);
}

function clockMs(): number {
  if (typeof __mseCurrentTime !== "function") return 0;
  try {
    return Math.round(__mseCurrentTime() * 1000);
  } catch (_e) {
    return 0;
  }
}

function durationMs(): number {
  if (typeof __mseDuration !== "function") return 0;
  try {
    const d = __mseDuration();
    if (d > 0 && isFinite(d)) return Math.round(d * 1000);
  } catch (_e) {}
  return 0;
}

let pendingFakeUmp: Uint8Array | null = null;
let fakeSeq = 0;
let fakeSeqVid = "";

function fmtRange(itag: number, w: { start: number; dur: number } | null): string {
  if (!w) return String(itag);
  return itag + ":" + w.start + "+" + w.dur;
}

function buildFakeUmp(
  playerTimeMs: number,
  needInit: boolean,
  audBr: string,
  fakeBr: string,
): Uint8Array | null {
  const row = pickFake144();
  if (!row) return null;
  const videoId = fake144VideoId();
  if (!videoId) return null;
  if (fakeSeqVid !== videoId) {
    fakeSeq = 0;
    fakeSeqVid = videoId;
  }
  const t = playerTimeMs > 0 ? playerTimeMs : clockMs();
  const startMs = t;
  const coverMs = 1000;
  fakeSeq++;
  const parts: Uint8Array[] = [];
  if (needInit) {
    parts.push(formatInitUmp(videoId, row.itag, FAKE_LMT_NUM, row.mime, durationMs()));
    parts.push(
      mediaHeaderUmp({
        hid: INIT_HID,
        videoId: videoId,
        itag: row.itag,
        lmt: FAKE_LMT_NUM,
        init: true,
        seq: 0,
        startMs: 0,
        durationMs: 0,
        startRange: 0,
        clen: INIT_BYTES.length,
      }),
    );
    parts.push(mediaDataUmp(INIT_HID, INIT_BYTES));
    parts.push(mediaEndUmp(INIT_HID));
  }
  parts.push(
    mediaHeaderUmp({
      hid: MEDIA_HID,
      videoId: videoId,
      itag: row.itag,
      lmt: FAKE_LMT_NUM,
      init: false,
      seq: fakeSeq,
      startMs: startMs,
      durationMs: coverMs,
      startRange: INIT_BYTES.length,
      clen: MEDIA_BYTES.length,
    }),
  );
  parts.push(mediaDataUmp(MEDIA_HID, MEDIA_BYTES));
  parts.push(mediaEndUmp(MEDIA_HID));
  console.log(
    "mse sabr fake-ump itag=" +
      row.itag +
      " init=" +
      (needInit ? "1" : "0") +
      " seq=" +
      fakeSeq +
      " start=" +
      startMs +
      " dur=" +
      coverMs +
      " t=" +
      t +
      " audBr=" +
      (audBr || "-") +
      " fakeBr=" +
      (fakeBr || "-"),
  );
  return concatU8(parts);
}

function rewriteAbr(u: Uint8Array): Uint8Array {
  const fs = readFields(u);
  if (!fs) return u;
  const fake = pickFake144();
  const fakeItag = fake ? fake.itag : -1;
  let playerTimeMs = 0;
  let selHasFake = false;
  const audParts: string[] = [];
  const fakeParts: string[] = [];
  const out: Field[] = [];
  const dropped: number[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 1 && f.w === 2 && f.v instanceof Uint8Array) {
      const cas = readFields(f.v);
      if (cas) {
        for (let j = 0; j < cas.length; j++) {
          const c = cas[j]!;
          if (c.n === 28 && c.w === 0) playerTimeMs = c.v as number;
        }
      }
      if (vodAudioOnly()) out.push({ n: 1, w: 2, v: rewriteCas40(f.v) });
      else out.push(f);
      continue;
    }
    if ((f.n === 17 || f.n === 2) && f.w === 2 && f.v instanceof Uint8Array) {
      const t = formatItag(f.v);
      if (t != null && t === fakeItag && f.n === 2) selHasFake = true;
      if (t != null && is144pItag(t)) {
        dropped.push(t);
        continue;
      }
    }
    if (f.n === 3 && f.w === 2 && f.v instanceof Uint8Array) {
      const t = rangeItag(f.v);
      const w = rangeWindow(f.v);
      if (t != null && is144pItag(t)) {
        fakeParts.push(fmtRange(t, w));
        dropped.push(t);
        continue;
      }
      if (t != null && isAudioItag(t)) audParts.push(fmtRange(t, w));
    }
    out.push(f);
  }
  if (dropped.length) console.log("mse sabr drop 144 itags=" + dropped.join(","));
  pendingFakeUmp = null;
  if (vodAudioOnly()) {
    pendingFakeUmp = buildFakeUmp(
      playerTimeMs,
      !selHasFake,
      audParts.join(","),
      fakeParts.join(","),
    );
  }
  if (!dropped.length && !vodAudioOnly()) return u;
  return writeFields(out);
}

export function prepareSabrBody(url: string, body: unknown): unknown {
  if (url.indexOf("/videoplayback") < 0) return body;
  const u = asU8(body);
  if (!u || u.length === 0) return body;
  return rewriteAbr(u);
}

/** Bytes to prepend on the matching SABR 200. Null if this request is not VOD zero. */
export function takePendingFakeUmp(): Uint8Array | null {
  const u = pendingFakeUmp;
  pendingFakeUmp = null;
  return u;
}

export function appendFakeUmp(body: Uint8Array): Uint8Array {
  const fake = takePendingFakeUmp();
  if (!fake || !fake.length) return body;
  return concatU8([body, fake]);
}
