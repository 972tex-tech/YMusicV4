// SABR VideoPlaybackAbrRequest: drop fake 144p from playable video (17),
// initialized formats (2), and buffered_ranges (3). Do not rewrite field 40.

import { is144pItag } from "../player/itag_144";

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

function rewriteAbr(u: Uint8Array): Uint8Array {
  const fs = readFields(u);
  if (!fs) return u;
  const out: Field[] = [];
  const dropped: number[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if ((f.n === 17 || f.n === 2) && f.w === 2 && f.v instanceof Uint8Array) {
      const t = formatItag(f.v);
      if (t != null && is144pItag(t)) {
        dropped.push(t);
        continue;
      }
    }
    if (f.n === 3 && f.w === 2 && f.v instanceof Uint8Array) {
      const t = rangeItag(f.v);
      if (t != null && is144pItag(t)) {
        dropped.push(t);
        continue;
      }
    }
    out.push(f);
  }
  if (!dropped.length) return u;
  console.log("mse sabr drop 144 itags=" + dropped.join(","));
  return writeFields(out);
}

export function prepareSabrBody(url: string, body: unknown): unknown {
  if (url.indexOf("/videoplayback") < 0) return body;
  const u = asU8(body);
  if (!u || u.length === 0) return body;
  return rewriteAbr(u);
}
