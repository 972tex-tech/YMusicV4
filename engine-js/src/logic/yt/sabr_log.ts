// SABR /videoplayback debug log (SABR_DEBUG compile define; stripped in prod).
// PO (query pot= + StreamerContext 19.2).
// clientAbrState: 28 playerTimeMs, 29 timeSinceLastSeekMs, 40 enabledTrackTypesBitfield.
// bufferedRanges: 1 formatId.itag, 2 startMs, 3 durationMs.
// selected_format_ids=2, selected_audio_format_ids=16, selected_video_format_ids=17.

import { isAudioOnly } from "../player/audio_only";
import { dumpBgOnSps, dumpFirstSps, recordSabrForDump } from "../player/bg_dump";
import { dumpDrain } from "../../protocol/mse";

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

function protoBytes(buf: Uint8Array, want: number): Uint8Array | null {
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) return null;
      if (field === want) return buf.subarray(i, i + len);
      i += len;
    } else return null;
  }
  return null;
}

function protoVarint(buf: Uint8Array, want: number): number | null {
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      if (field === want) return n.v;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      i += n.v;
      if (i > buf.length) return null;
    } else return null;
  }
  return null;
}

function protoString(buf: Uint8Array, want: number): string | null {
  const b = protoBytes(buf, want);
  if (!b) return null;
  let s = "";
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]!);
  return s;
}

function looksAscii(buf: Uint8Array): boolean {
  if (buf.length === 0) return false;
  for (let i = 0; i < buf.length; i++) {
    const c = buf[i]!;
    if (c < 32 || c > 126) return false;
  }
  return true;
}

function asciiOf(buf: Uint8Array): string {
  let s = "";
  for (let i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]!);
  return s;
}

function fmtFormatId(p: Uint8Array): string {
  const itag = protoVarint(p, 1);
  const lmt = protoVarint(p, 2);
  const xtag = protoString(p, 3);
  let s = "itag=" + (itag != null ? itag : "-");
  if (lmt != null) s += " lmt=" + lmt;
  if (xtag) s += " xtag=" + xtag;
  return s;
}

/** Compact protobuf field dump. Nested FormatId on 2/13, TimeRange on 15. */
function fmtProto(buf: Uint8Array): string {
  const parts: string[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) break;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      parts.push(field + "=" + n.v);
    } else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) break;
      const inner = buf.subarray(i, i + len);
      i += len;
      if (field === 13) parts.push("13{" + fmtFormatId(inner) + "}");
      else if (field === 15) parts.push("tr{" + fmtProto(inner) + "}");
      else if (looksAscii(inner) && inner.length < 80)
        parts.push(field + "=\"" + asciiOf(inner) + "\"");
      else parts.push(field + ":len=" + len);
    } else if (wire === 1) {
      i += 8;
      parts.push(field + ":i64");
    } else if (wire === 5) {
      i += 4;
      parts.push(field + ":i32");
    } else break;
  }
  return parts.join(" ");
}

function fmtFormatInit(p: Uint8Array): string {
  const vid = protoString(p, 1);
  const fid = protoBytes(p, 2);
  let s = "FORMAT_INIT";
  if (vid) s += " vid=" + vid;
  if (fid) s += " " + fmtFormatId(fid);
  const rest = fmtProto(p);
  if (rest) s += " [" + rest + "]";
  return s;
}

function fmtMediaHeader(p: Uint8Array): string {
  const hid = protoVarint(p, 1);
  const vid = protoString(p, 2);
  const itag = protoVarint(p, 3);
  const lmt = protoVarint(p, 4);
  const range = protoVarint(p, 6);
  const init = protoVarint(p, 8);
  const seq = protoVarint(p, 9);
  const t = protoVarint(p, 11);
  const dur = protoVarint(p, 12);
  const fid = protoBytes(p, 13);
  const clen = protoVarint(p, 14);
  const tr = protoBytes(p, 15);
  let s = "MEDIA_HEADER hid=" + (hid != null ? hid : "-");
  if (itag != null) s += " itag=" + itag;
  else if (fid) s += " " + fmtFormatId(fid);
  s += " init=" + (init === 1 ? "1" : "0");
  if (seq != null) s += " seq=" + seq;
  if (t != null) s += " startMs=" + t;
  if (dur != null) s += " durMs=" + dur;
  if (range != null) s += " range=" + range;
  if (clen != null) s += " clen=" + clen;
  if (lmt != null) s += " lmt=" + lmt;
  if (vid) s += " vid=" + vid;
  if (tr) s += " tr{" + fmtProto(tr) + "}";
  const rest = fmtProto(p);
  if (rest) s += " [" + rest + "]";
  return s;
}

function protoRepeated(buf: Uint8Array, want: number): Uint8Array[] {
  const out: Uint8Array[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) break;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) break;
      if (field === want) out.push(buf.subarray(i, i + len));
      i += len;
    } else break;
  }
  return out;
}

function clockMs(): string {
  if (typeof __mseCurrentTime !== "function") return "-";
  try {
    return String(Math.round(__mseCurrentTime() * 1000));
  } catch (_e) {
    return "err";
  }
}

/** Flatten __mseBuffered pairs to the same startMs+durationMs shape as SABR. */
function msePairs(audio: number): string {
  if (typeof __mseBuffered !== "function") return "";
  try {
    const r = __mseBuffered(audio);
    if (!r || r.length < 2) return "";
    const parts: string[] = [];
    for (let i = 0; i + 1 < r.length; i += 2) {
      const a = r[i]!;
      const b = r[i + 1]!;
      parts.push(Math.round(a * 1000) + "+" + Math.round((b - a) * 1000));
    }
    return parts.join(",");
  } catch (_e) {
    return "err";
  }
}

function durMs(): string {
  if (typeof __mseDuration !== "function") return "-";
  try {
    return String(Math.round(__mseDuration() * 1000));
  } catch (_e) {
    return "err";
  }
}

function b64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** UMP varint — not protobuf. Prefix byte selects width (yt-media Ump.kt). */
function readUmpVarint(buf: Uint8Array, i: number): { v: number; i: number } | null {
  if (i >= buf.length) return null;
  const prefix = buf[i++]!;
  if (prefix < 0x80) return { v: prefix, i: i };
  if (prefix < 0xc0) {
    if (i >= buf.length) return null;
    return { v: (prefix & 0x3f) + 64 * buf[i]!, i: i + 1 };
  }
  if (prefix < 0xe0) {
    if (i + 1 >= buf.length) return null;
    return { v: (prefix & 0x1f) + 32 * (buf[i]! + 256 * buf[i + 1]!), i: i + 2 };
  }
  if (prefix < 0xf0) {
    if (i + 2 >= buf.length) return null;
    return {
      v: (prefix & 0x0f) + 16 * (buf[i]! + 256 * (buf[i + 1]! + 256 * buf[i + 2]!)),
      i: i + 3,
    };
  }
  if (i + 3 >= buf.length) return null;
  return {
    v: buf[i]! + 256 * (buf[i + 1]! + 256 * (buf[i + 2]! + 256 * buf[i + 3]!)),
    i: i + 4,
  };
}

const UMP_NAME: Record<number, string> = {
  20: "MEDIA_HEADER",
  21: "MEDIA_DATA",
  22: "MEDIA_END",
  31: "LIVE_METADATA",
  35: "NEXT_REQUEST_POLICY",
  42: "FORMAT_INIT",
  43: "SABR_REDIRECT",
  44: "SABR_ERROR",
  57: "SABR_CONTEXT_UPDATE",
  58: "STREAM_PROTECTION_STATUS",
};

function spsLabel(v: number): string {
  if (v === 1) return "ok";
  if (v === 2 || v === 3) return "po-rejected";
  return "?";
}

/** MEDIA_HEADER sequence_number = field 9. Skip init (field 8) and seq 0. */
function maxMhSeq(body: Uint8Array): number | null {
  let i = 0;
  let m: number | null = null;
  while (i < body.length) {
    const id = readUmpVarint(body, i);
    if (!id) break;
    const sz = readUmpVarint(body, id.i);
    if (!sz) break;
    const start = sz.i;
    const end = start + sz.v;
    if (end > body.length) break;
    if (id.v === 20) {
      const p = body.subarray(start, end);
      if (protoVarint(p, 8) !== 1) {
        const seq = protoVarint(p, 9);
        if (seq != null && seq > 0 && (m == null || seq > m)) m = seq;
      }
    }
    i = end;
  }
  return m;
}

export function logSabrResponse(url: string, status: number, body: Uint8Array): void {
  if (url.indexOf("/videoplayback") < 0) return;
  const audioOnly = isAudioOnly();
  const parts: string[] = [];
  let i = 0;
  let sps = -1;
  let err = "";
  let media = 0;
  while (i < body.length) {
    const id = readUmpVarint(body, i);
    if (!id) break;
    const sz = readUmpVarint(body, id.i);
    if (!sz) break;
    const start = sz.i;
    const end = start + sz.v;
    if (end > body.length) {
      parts.push((UMP_NAME[id.v] || String(id.v)) + ":trunc");
      break;
    }
    const name = UMP_NAME[id.v] || String(id.v);
    const payload = body.subarray(start, end);
    if (id.v === 21) media += sz.v;
    if (id.v === 58) {
      const v = protoVarint(payload, 1);
      sps = v != null ? v : -1;
      parts.push("SPS=" + sps + "(" + spsLabel(sps) + ")");
    } else if (id.v === 44) {
      err = "err";
      parts.push("SABR_ERROR");
    } else if (id.v === 43) {
      parts.push("REDIRECT");
    } else if (id.v === 42) {
      const fi = fmtFormatInit(payload);
      const fid = protoBytes(payload, 2);
      const itag = fid ? protoVarint(fid, 1) : protoVarint(payload, 3);
      parts.push("FORMAT_INIT:itag=" + (itag != null ? itag : "-"));
      if (SABR_DEBUG) console.log("sabr " + fi);
    } else if (id.v === 20) {
      const mh = fmtMediaHeader(payload);
      const itag = protoVarint(payload, 3);
      const init = protoVarint(payload, 8) === 1 ? 1 : 0;
      const seq = protoVarint(payload, 9);
      const t = protoVarint(payload, 11);
      parts.push(
        "MH:itag=" +
          (itag != null ? itag : "-") +
          ":init=" +
          init +
          (seq != null ? ":seq=" + seq : "") +
          (t != null ? ":t=" + t : ""),
      );
      if (SABR_DEBUG) console.log("sabr " + mh);
    } else {
      parts.push(name + ":" + sz.v);
    }
    i = end;
  }
  const seq = maxMhSeq(body);
  if (SABR_DEBUG) {
    let line =
      "sabr HTTP " +
      status +
      " body=" +
      body.length +
      " ump=[" +
      parts.join(",") +
      "]" +
      " media=" +
      media +
      " sps=" +
      sps;
    if (seq != null) line += " mhSeq=" + seq;
    if (sps === 2 || sps === 3) line += " PO_REJECTED";
    if (err) line += " SABR_ERROR";
    if (body.length > 0 && body.length <= 256) {
      let hx = "";
      for (let hi = 0; hi < body.length; hi++)
        hx += (body[hi]! < 16 ? "0" : "") + body[hi]!.toString(16);
      line += " hex=" + hx;
    }
    console.log(line);
  }
  dumpFirstSps(sps);
  if (sps !== 1 && sps > 0) dumpBgOnSps(sps);
  if (MSE_DEBUG && audioOnly) {
    dumpDrain(media > 0 ? "sabr-media" : "sabr-res");
    if (media > 0) {
      setTimeout(function () {
        dumpDrain("sabr-media+0");
      }, 0);
    }
  }
}

function itagsOf(msgs: Uint8Array[]): string {
  const out: number[] = [];
  for (let i = 0; i < msgs.length; i++) {
    const t = protoVarint(msgs[i]!, 1);
    if (t != null) out.push(t);
  }
  return out.length ? out.join(",") : "-";
}

export function logSabrRequest(url: string, body?: unknown, status?: number): void {
  if (url.indexOf("/videoplayback") < 0) return;
  const u = asU8(body);
  recordSabrForDump(url, u);
  if (!SABR_DEBUG) return;
  const q = (url.match(/[?&]pot=([^&]*)/) || [])[1] || "";
  let potB = 0;
  let potB64 = "";
  let playerTimeMs = -1;
  let sinceSeekMs = -1;
  let bit40 = -1;
  let bit46 = -1;
  let br = "";
  let sel = "-";
  let aud = "-";
  let vid = "-";
  if (u && u.length > 0) {
    const sc = protoBytes(u, 19);
    if (sc) {
      const pot = protoBytes(sc, 2);
      if (pot) {
        potB = pot.length;
        potB64 = b64url(pot);
      }
    }
    const cas = protoBytes(u, 1);
    if (cas) {
      const t = protoVarint(cas, 28);
      if (t != null) playerTimeMs = t;
      const s = protoVarint(cas, 29);
      if (s != null) sinceSeekMs = s;
      const f40 = protoVarint(cas, 40);
      if (f40 != null) bit40 = f40;
      const f46 = protoVarint(cas, 46);
      if (f46 != null) bit46 = f46;
    }
    const ranges = protoRepeated(u, 3);
    const parts: string[] = [];
    for (let i = 0; i < ranges.length && i < 8; i++) {
      const fid = protoBytes(ranges[i]!, 1);
      const itag = fid ? protoVarint(fid, 1) : null;
      const st = protoVarint(ranges[i]!, 2);
      const d = protoVarint(ranges[i]!, 3);
      parts.push(
        (itag != null ? itag + ":" : "") + (st != null ? st : "?") + "+" + (d != null ? d : "?"),
      );
    }
    br = parts.join(",");
    sel = itagsOf(protoRepeated(u, 2));
    aud = itagsOf(protoRepeated(u, 16));
    vid = itagsOf(protoRepeated(u, 17));
  }
  const sabr = url.indexOf("sabr=") >= 0 ? "1" : "0";
  const st = status != null ? " HTTP " + status : "";
  console.log(
    "sabr" +
      st +
      " sabr=" +
      sabr +
      " audioOnly=" +
      (isAudioOnly() ? "1" : "0") +
      " 40=" +
      bit40 +
      " 46=" +
      bit46 +
      " playerTimeMs=" +
      playerTimeMs +
      " sinceSeekMs=" +
      sinceSeekMs +
      " clock=" +
      clockMs() +
      " dur=" +
      durMs() +
      " sel=" +
      sel +
      " aud=" +
      aud +
      " vid=" +
      vid +
      " br=[" +
      br +
      "]" +
      " mse=[a:" +
      msePairs(1) +
      " v:" +
      msePairs(0) +
      "]" +
      " potQ=" +
      q.length +
      " potBody=" +
      potB +
      " pot=" +
      potB64,
  );
  if (MSE_DEBUG && isAudioOnly()) dumpDrain("sabr-req");
}
