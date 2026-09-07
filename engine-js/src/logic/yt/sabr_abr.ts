// SABR VideoPlaybackAbrRequest: audio-only is ClientAbrState field 40 = 1
// (enabledTrackTypesBitfield).

import { isAudioOnly } from "../player/audio_only";

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

function rewriteCas(cas: Uint8Array, audioOnly: boolean): Uint8Array {
  const fs = readFields(cas);
  if (!fs) return cas;
  const out: Field[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (audioOnly && (f.n === 40 || f.n === 46)) continue;
    if (!audioOnly && f.n === 16 && f.w === 0 && f.v === 1) continue;
    out.push(f);
  }
  if (audioOnly) {
    out.push({ n: 40, w: 0, v: 1 });
    out.push({ n: 46, w: 0, v: 1 });
  }
  return writeFields(out);
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

function isAudioItag(t: number): boolean {
  return (
    t === 139 ||
    t === 140 ||
    t === 141 ||
    t === 249 ||
    t === 250 ||
    t === 251 ||
    t === 599 ||
    t === 600
  );
}

function rewriteAbr(u: Uint8Array, audioOnly: boolean): Uint8Array {
  const fs = readFields(u);
  if (!fs) return u;
  const audioItags: number[] = [];
  if (audioOnly) {
    for (let i = 0; i < fs.length; i++) {
      const f = fs[i]!;
      if (f.n === 16 && f.w === 2 && f.v instanceof Uint8Array) {
        const t = formatItag(f.v);
        if (t != null) audioItags.push(t);
      }
    }
  }
  const keepItag = function (t: number | null): boolean {
    if (t == null) return true;
    if (audioItags.length) {
      for (let i = 0; i < audioItags.length; i++) if (audioItags[i] === t) return true;
      return false;
    }
    return isAudioItag(t);
  };
  const out: Field[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.n === 1 && f.w === 2 && f.v instanceof Uint8Array) {
      out.push({ n: 1, w: 2, v: rewriteCas(f.v, audioOnly) });
      continue;
    }
    if (audioOnly) {
      /* Dummy video TimeRanges must not appear as SABR leftover. The server
         then skips real audio, and YouTube never appendBuffer's it (log23). */
      if (f.n === 17) continue;
      if (f.n === 2 && f.w === 2 && f.v instanceof Uint8Array && !keepItag(formatItag(f.v)))
        continue;
      if (f.n === 3 && f.w === 2 && f.v instanceof Uint8Array && !keepItag(rangeItag(f.v)))
        continue;
    }
    out.push(f);
  }
  return writeFields(out);
}

/** Rewrite /videoplayback ABR POST when audio-only (incl. live). Auto/live
 *  bodies stay untouched — re-encoding those left SABR with sel=- and no media. */
export function prepareSabrBody(url: string, body: unknown): unknown {
  if (url.indexOf("/videoplayback") < 0) return body;
  if (!isAudioOnly()) return body;
  const u = asU8(body);
  if (!u || u.length === 0) return body;
  return rewriteAbr(u, true);
}
