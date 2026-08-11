// SAPISIDHASH authorization for authenticated youtubei calls (docs/innertube/auth.md). YouTube's web
// client signs every authenticated request with SHA1("<ts> <cookie> <origin>") over the SAPISID
// cookie (plus the 1P/3P variants). The crypto primitive lives HERE — auth logic stays in the
// bundle, not the app — and mirrors :accounts SapisidHash.kt (same known vectors).

const PAPISID_1P = "__Secure-1PAPISID";
const PAPISID_3P = "__Secure-3PAPISID";

/** UTF-8 bytes of `str` (surrogate pairs folded to a 4-byte sequence). */
function utf8Bytes(str: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
      const c2 = str.charCodeAt(i + 1);
      c = 0x10000 + ((c & 0x3ff) << 10) + (c2 & 0x3ff);
      i++;
      out.push(
        0xf0 | (c >> 18),
        0x80 | ((c >> 12) & 0x3f),
        0x80 | ((c >> 6) & 0x3f),
        0x80 | (c & 0x3f),
      );
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return out;
}

function hex8(n: number): string {
  let s = (n >>> 0).toString(16);
  while (s.length < 8) s = "0" + s;
  return s;
}

/** SHA-1 of `input`'s UTF-8 bytes, lowercase hex. */
export function sha1Hex(input: string): string {
  const src = utf8Bytes(input);
  const ml = src.length * 8;
  src.push(0x80);
  while (src.length % 64 !== 56) src.push(0);
  const hi = Math.floor(ml / 0x100000000);
  const lo = ml >>> 0;
  src.push((hi >>> 24) & 0xff, (hi >>> 16) & 0xff, (hi >>> 8) & 0xff, hi & 0xff);
  src.push((lo >>> 24) & 0xff, (lo >>> 16) & 0xff, (lo >>> 8) & 0xff, lo & 0xff);
  // Typed arrays index as `number` (not `number | undefined` under noUncheckedIndexedAccess).
  const bytes = Uint8Array.from(src);

  let h0 = 0x67452301;
  let h1 = 0xefcdab89;
  let h2 = 0x98badcfe;
  let h3 = 0x10325476;
  let h4 = 0xc3d2e1f0;
  const w = new Uint32Array(80);
  for (let off = 0; off < bytes.length; off += 64) {
    for (let i = 0; i < 16; i++) {
      const j = off + i * 4;
      // in-range by construction (bytes is 64-byte aligned); ! elides the index-access widening.
      w[i] =
        ((bytes[j]! << 24) | (bytes[j + 1]! << 16) | (bytes[j + 2]! << 8) | bytes[j + 3]!) >>> 0;
    }
    for (let i = 16; i < 80; i++) {
      const v = w[i - 3]! ^ w[i - 8]! ^ w[i - 14]! ^ w[i - 16]!;
      w[i] = ((v << 1) | (v >>> 31)) >>> 0;
    }
    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let i = 0; i < 80; i++) {
      let f: number;
      let k: number;
      if (i < 20) {
        f = (b & c) | (~b & d);
        k = 0x5a827999;
      } else if (i < 40) {
        f = b ^ c ^ d;
        k = 0x6ed9eba1;
      } else if (i < 60) {
        f = (b & c) | (b & d) | (c & d);
        k = 0x8f1bbcdc;
      } else {
        f = b ^ c ^ d;
        k = 0xca62c1d6;
      }
      const tmp = (((a << 5) | (a >>> 27)) + f + e + k + w[i]!) >>> 0;
      e = d;
      d = c;
      c = ((b << 30) | (b >>> 2)) >>> 0;
      b = a;
      a = tmp;
    }
    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
  }
  return hex8(h0) + hex8(h1) + hex8(h2) + hex8(h3) + hex8(h4);
}

/** The scheme+host of `url` (the origin the SAPISIDHASH is bound to). */
export function originOf(url: string): string {
  const m = /^(https?:\/\/[^/]+)/.exec(url);
  return m !== null && m[1] !== undefined ? m[1] : url;
}

/**
 * The `Authorization` header value for an authenticated youtubei call under this cookie `map`, or
 * undefined when there's no SAPISID (not signed in). Multi-hash form — SAPISIDHASH plus the 1P/3P
 * hashes when those cookies exist — matching what the web client sends. `origin` is scheme+host.
 *
 * When `userSessionId` (ytcfg USER_SESSION_ID) is present the hash input is prefixed with it and the
 * `_u` suffix is appended to each hash — the signed-in form the web client emits (legacy
 * getSAPISIDHASH); without it, the plain `<ts>_<hash>` form.
 */
export function authorizationHeader(
  map: Record<string, string | undefined>,
  origin: string,
  userSessionId: string | undefined,
): string | undefined {
  // SAPISID is normally a COPY of __Secure-3PAPISID — when only the classic name is gone (a
  // half-wiped jar tombstoned it while the __Secure twins survived, seen live 2026-07-31), the
  // twins still authenticate; hash from them rather than dropping auth entirely.
  const sapisid = nonEmpty(map["SAPISID"]) ?? nonEmpty(map[PAPISID_3P]) ?? nonEmpty(map[PAPISID_1P]);
  if (sapisid === undefined) return undefined;
  const ts = Math.floor(Date.now() / 1000);
  const parts: string[] = [];
  pushHash(parts, "SAPISIDHASH", ts, sapisid, origin, userSessionId);
  pushHash(parts, "SAPISID1PHASH", ts, map[PAPISID_1P], origin, userSessionId);
  pushHash(parts, "SAPISID3PHASH", ts, map[PAPISID_3P], origin, userSessionId);
  return parts.join(" ");
}

function nonEmpty(v: string | undefined): string | undefined {
  return v !== undefined && v.length > 0 ? v : undefined;
}

function pushHash(
  out: string[],
  label: string,
  ts: number,
  value: string | undefined,
  origin: string,
  userSessionId: string | undefined,
): void {
  if (value === undefined || value.length === 0) return;
  const authed = userSessionId !== undefined && userSessionId.length > 0;
  const input = (authed ? userSessionId + " " : "") + ts + " " + value + " " + origin;
  out.push(label + " " + ts + "_" + sha1Hex(input) + (authed ? "_u" : ""));
}
