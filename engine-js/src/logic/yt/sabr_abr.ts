// SABR VideoPlaybackAbrRequest: drop fake 144p from playable video (17),
// initialized formats (2), and buffered_ranges (3). Product-zero also
// sets ClientAbrState field 40=1 (server sends no real video) and appends a
// fake 144p UMP: one media segment covering 0–duration.

import { isProductZero } from "../player/audio_only";
import { fake144VideoId, is144pItag, pickFake144 } from "../player/itag_144";
import type { Fake144Row } from "../player/itag_144";
import { appendRestCover } from "../../protocol/mse";

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
  if (!(v > 0)) return [0];
  /* Do not use `&` — JS bitwise ops are 32-bit. lastModified is ~1.7e15. */
  while (v >= 128) {
    const low = v % 128;
    out.push(low + 128);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
}

function decToNum(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const d = s.charCodeAt(i) - 48;
    if (d < 0 || d > 9) continue;
    n = n * 10 + d;
  }
  return n;
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
  if (v < 2097152) return [(v & 0x1f) | 0xc0, (v >>> 5) & 0xff, (v >>> 13) & 0xff];
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

function encodeFormatId(itag: number, lmtDec: string): Uint8Array {
  return writeFields([
    { n: 1, w: 0, v: itag },
    { n: 2, w: 0, v: decToNum(lmtDec) },
  ]);
}

function initLen(row: Fake144Row): number {
  return row.initEnd - row.initStart + 1 + (row.indexEnd - row.indexStart + 1);
}

function initBytes(row: Fake144Row): Uint8Array {
  const n = initLen(row);
  const u = new Uint8Array(n > 0 ? n : 4);
  u[0] = 0x59;
  u[1] = 0x4d;
  u[2] = 0x46;
  u[3] = 0x30;
  return u;
}

function writeU64LE(u: Uint8Array, off: number, v: number): void {
  let n = Math.floor(v);
  if (n < 0) n = 0;
  for (let i = 0; i < 8; i++) {
    u[off + i] = n % 256;
    n = Math.floor(n / 256);
  }
}

function encodeRange(start: number, end: number): Uint8Array {
  return writeFields([
    { n: 1, w: 0, v: start },
    { n: 2, w: 0, v: end },
  ]);
}

/** YMF1 + start_ms + duration_ms. C leftover reports this as buffered. */
function fakeMediaBytes(startMs: number, durationMs: number): Uint8Array {
  const u = new Uint8Array(20);
  u[0] = 0x59;
  u[1] = 0x4d;
  u[2] = 0x46;
  u[3] = 0x31;
  writeU64LE(u, 4, startMs);
  writeU64LE(u, 12, durationMs);
  return u;
}

function formatInitUmp(videoId: string, row: Fake144Row, durationMs: number): Uint8Array {
  const fid = encodeFormatId(row.itag, row.lmt);
  const fs: Field[] = [
    { n: 1, w: 2, v: asciiU8(videoId) },
    { n: 2, w: 2, v: fid },
  ];
  if (durationMs > 0) fs.push({ n: 3, w: 0, v: durationMs });
  fs.push({ n: 4, w: 0, v: 144 });
  fs.push({ n: 5, w: 2, v: asciiU8(row.mime) });
  fs.push({ n: 6, w: 2, v: encodeRange(row.initStart, row.initEnd) });
  fs.push({ n: 7, w: 2, v: encodeRange(row.indexStart, row.indexEnd) });
  fs.push({ n: 9, w: 0, v: durationMs > 0 ? durationMs : 1000 });
  fs.push({ n: 10, w: 0, v: 1000 });
  return umpPart(42, writeFields(fs));
}

function mediaHeaderUmp(opts: {
  hid: number;
  videoId: string;
  itag: number;
  lmt: string;
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
    { n: 4, w: 0, v: decToNum(opts.lmt) },
    { n: 6, w: 0, v: opts.startRange },
    { n: 13, w: 2, v: fid },
    { n: 14, w: 0, v: opts.clen },
  ];
  if (opts.init) fs.push({ n: 8, w: 0, v: 1 });
  else fs.push({ n: 8, w: 0, v: 0 });
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

function durationMs(): number {
  if (typeof __mseDuration !== "function") return 0;
  try {
    const d = __mseDuration();
    if (d > 0 && isFinite(d)) return Math.round(d * 1000);
  } catch (_e) {}
  return 0;
}

function readUmpVarint(buf: Uint8Array, i: number): { v: number; i: number } | null {
  if (i >= buf.length) return null;
  const p = buf[i++]!;
  if (p < 0x80) return { v: p, i: i };
  if (i >= buf.length) return null;
  if (p < 0xc0) return { v: (p & 0x3f) + 64 * buf[i++]!, i: i };
  if (i + 1 >= buf.length) return null;
  if (p < 0xe0) return { v: (p & 0x1f) + 32 * (buf[i]! + 256 * buf[i + 1]!), i: i + 2 };
  if (i + 2 >= buf.length) return null;
  if (p < 0xf0)
    return {
      v: (p & 0x0f) + 16 * (buf[i]! + 256 * (buf[i + 1]! + 256 * buf[i + 2]!)),
      i: i + 3,
    };
  if (i + 3 >= buf.length) return null;
  return {
    v: buf[i]! + 256 * (buf[i + 1]! + 256 * (buf[i + 2]! + 256 * buf[i + 3]!)),
    i: i + 4,
  };
}

function maxMediaHid(body: Uint8Array): number {
  let i = 0;
  let max = -1;
  while (i < body.length) {
    const id = readUmpVarint(body, i);
    if (!id) break;
    i = id.i;
    const sz = readUmpVarint(body, i);
    if (!sz) break;
    i = sz.i;
    if (id.v === 20 && sz.v > 0 && i + sz.v <= body.length) {
      const fs = readFields(body.subarray(i, i + sz.v));
      if (fs) {
        for (let j = 0; j < fs.length; j++) {
          const f = fs[j]!;
          if (f.n === 1 && f.w === 0) {
            const h = f.v as number;
            if (h > max) max = h;
          }
        }
      }
    }
    i += sz.v;
  }
  return max;
}

type PendingCover = {
  needInit: boolean;
  audBr: string;
  fakeBr: string;
};
let pendingCover: PendingCover | null = null;

function fmtRange(itag: number, w: { start: number; dur: number } | null): string {
  if (!w) return String(itag);
  return itag + ":" + w.start + "+" + w.dur;
}

function buildFakeUmp(
  needInit: boolean,
  audBr: string,
  fakeBr: string,
  initHid: number,
  mediaHid: number,
): Uint8Array | null {
  const row = pickFake144();
  if (!row) return null;
  const videoId = fake144VideoId();
  if (!videoId) return null;
  const dur = durationMs();
  const init = initBytes(row);
  const media = fakeMediaBytes(0, dur);
  const parts: Uint8Array[] = [];
  if (needInit) {
    parts.push(formatInitUmp(videoId, row, dur));
    parts.push(
      mediaHeaderUmp({
        hid: initHid,
        videoId: videoId,
        itag: row.itag,
        lmt: row.lmt,
        init: true,
        seq: 0,
        startMs: 0,
        durationMs: 0,
        startRange: row.initStart,
        clen: init.length,
      }),
    );
    parts.push(mediaDataUmp(initHid, init));
    parts.push(mediaEndUmp(initHid));
  }
  parts.push(
    mediaHeaderUmp({
      hid: mediaHid,
      videoId: videoId,
      itag: row.itag,
      lmt: row.lmt,
      init: false,
      seq: 1,
      startMs: 0,
      durationMs: dur,
      startRange: init.length,
      clen: media.length,
    }),
  );
  parts.push(mediaDataUmp(mediaHid, media));
  parts.push(mediaEndUmp(mediaHid));
  if (needInit) appendRestCover(init);
  appendRestCover(media);
  console.log(
    "mse sabr fake-ump itag=" +
      row.itag +
      " lmt=" +
      row.lmt +
      " mime=" +
      row.mime +
      " init=" +
      (needInit ? "1" : "0") +
      " hid=" +
      initHid +
      "," +
      mediaHid +
      " seq=1 start=0 dur=" +
      dur +
      " range=" +
      init.length +
      " clen=" +
      media.length +
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
  let selHasFake = false;
  const audParts: string[] = [];
  const fakeParts: string[] = [];
  const out: Field[] = [];
  const dropped: number[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 1 && f.w === 2 && f.v instanceof Uint8Array) {
      if (isProductZero()) out.push({ n: 1, w: 2, v: rewriteCas40(f.v) });
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
  pendingCover = null;
  if (isProductZero()) {
    pendingCover = {
      needInit: !selHasFake,
      audBr: audParts.join(","),
      fakeBr: fakeParts.join(","),
    };
  }
  if (!dropped.length && !isProductZero()) return u;
  return writeFields(out);
}

export function prepareSabrBody(url: string, body: unknown): unknown {
  if (url.indexOf("/videoplayback") < 0) return body;
  const u = asU8(body);
  if (!u || u.length === 0) return body;
  return rewriteAbr(u);
}

export function hasPendingFake(): boolean {
  return pendingCover != null;
}

function emitPending(initHid: number, mediaHid: number): Uint8Array | null {
  const p = pendingCover;
  pendingCover = null;
  if (!p) return null;
  return buildFakeUmp(p.needInit, p.audBr, p.fakeBr, initHid, mediaHid);
}

export function appendFakeUmp(body: Uint8Array): Uint8Array {
  if (!pendingCover) return body;
  const max = maxMediaHid(body);
  const fake = emitPending((max + 1) & 255, (max + 2) & 255);
  if (!fake || !fake.length) return body;
  return concatU8([body, fake]);
}
