// Cache /player JSON (pre–fake-144p) plus decoded SABR URLs captured from the
// player's own googlevideo requests. Download uses that; we do not decipher.

import { debug } from "../../debug";

const CACHE_MS = 3 * 60 * 1000;
const WAIT_MS = 20_000;

type Waiter = (ok: boolean) => void;

type Entry = {
  rec: Record<string, unknown> | null;
  sabrUrls: string[];
  expires: number;
  waiters: Waiter[];
};

const cache: Record<string, Entry> = {};
/** googlevideo `id=o-…` → videoId. SABR URLs have no `v=`. */
const streamToVid: Record<string, string> = {};
let lastPlayerVid = "";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function now(): number {
  return Date.now();
}

function prune(): void {
  const t = now();
  for (const id in cache) {
    const e = cache[id];
    if (e && e.expires < t && !e.waiters.length) delete cache[id];
  }
}

function complete(e: Entry): boolean {
  return !!(e.rec && e.sabrUrls.length);
}

function flush(e: Entry, ok: boolean): void {
  const w = e.waiters;
  e.waiters = [];
  for (let i = 0; i < w.length; i++) w[i]!(ok);
}

function entry(id: string): Entry {
  let e = cache[id];
  if (!e) {
    e = { rec: null, sabrUrls: [], expires: now() + CACHE_MS, waiters: [] };
    cache[id] = e;
  }
  return e;
}

function simpleName(v: unknown): string {
  if (typeof v === "string") return v;
  const o = asRec(v);
  if (o && typeof o["simpleText"] === "string") return o["simpleText"] as string;
  return "";
}

function ytcfgStr(key: string): string {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  const get = cfg ? cfg["get"] : null;
  if (typeof get === "function") {
    const v = (get as (k: string) => unknown)(key);
    return typeof v === "string" ? v : "";
  }
  const data = cfg ? asRec(cfg["data_"]) : null;
  const v = data ? data[key] : null;
  return typeof v === "string" ? v : "";
}

function flattenCaptions(rec: Record<string, unknown>): Record<string, unknown>[] {
  const caps = asRec(rec["captions"]);
  const list = caps ? asRec(caps["playerCaptionsTracklistRenderer"]) : null;
  const raw = list ? list["captionTracks"] : null;
  const out: Record<string, unknown>[] = [];
  if (!Array.isArray(raw)) return out;
  for (let i = 0; i < raw.length; i++) {
    const t = asRec(raw[i]);
    if (!t) continue;
    const url = typeof t["baseUrl"] === "string" ? t["baseUrl"] : "";
    const lang = typeof t["languageCode"] === "string" ? t["languageCode"] : "";
    if (!url || !lang) continue;
    const kind = typeof t["kind"] === "string" ? t["kind"] : "";
    const vss = typeof t["vssId"] === "string" ? t["vssId"] : "";
    const name = simpleName(t["name"]) || lang;
    out.push({
      "languageCode": lang,
      "name": kind === "asr" ? name + " (auto)" : name,
      "baseUrl": url,
      "isAsr": kind === "asr",
      "isDefault": vss.indexOf(".") === 0,
    });
  }
  return out;
}

function ustreamerConfig(rec: Record<string, unknown>): string {
  const pc = asRec(rec["playerConfig"]);
  const mc = pc ? asRec(pc["mediaCommonConfig"]) : null;
  const ur = mc ? asRec(mc["mediaUstreamerRequestConfig"]) : null;
  const v = ur ? ur["videoPlaybackUstreamerConfig"] : null;
  return typeof v === "string" ? v : "";
}

const BACKUP: Record<string, string> = {
  "_lastModified": "lastModified",
  "_mimeType": "mimeType",
  "_xtags": "xtags",
  "_xtag": "xtag",
  "_initRange": "initRange",
  "_indexRange": "indexRange",
  "_contentLength": "contentLength",
};

function restoreFormat(fmt: Record<string, unknown>): Record<string, unknown> {
  let touched = false;
  for (const b in BACKUP) {
    if (fmt[b] !== undefined) {
      touched = true;
      break;
    }
  }
  if (!touched) return fmt;
  const out: Record<string, unknown> = {};
  for (const k in fmt) {
    if (k.charAt(0) === "_") continue;
    out[k] = fmt[k];
  }
  for (const b in BACKUP) {
    if (fmt[b] !== undefined) out[BACKUP[b]!] = fmt[b];
  }
  return out;
}

function streamingForHost(sd: Record<string, unknown>, sabrUrl: string): Record<string, unknown> {
  const streaming: Record<string, unknown> = {};
  for (const k in sd) streaming[k] = sd[k];
  const fmts = sd["adaptiveFormats"];
  if (Array.isArray(fmts)) {
    const restored: unknown[] = [];
    for (let i = 0; i < fmts.length; i++) {
      const f = asRec(fmts[i]);
      restored.push(f ? restoreFormat(f) : fmts[i]);
    }
    streaming["adaptiveFormats"] = restored;
  }
  if (sabrUrl) streaming["serverAbrStreamingUrl"] = sabrUrl;
  return streaming;
}

function buildDownloadJson(e: Entry): Record<string, unknown> {
  const rec = e.rec!;
  const sd = asRec(rec["streamingData"]) || {};
  const sabr = e.sabrUrls.length ? e.sabrUrls[e.sabrUrls.length - 1]! : "";
  const streaming = streamingForHost(sd, sabr);
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  const js = ytcfgStr("PLAYER_JS_URL");
  return {
    "clientName": "WEB",
    "clientNameId": 1,
    "clientVersion": cver,
    "playerJs": js,
    "videoDetails": rec["videoDetails"],
    "microformat": rec["microformat"],
    "playabilityStatus": rec["playabilityStatus"],
    "streamingData": streaming,
    "captions": rec["captions"],
    "captionTracks": flattenCaptions(rec),
    "videoPlaybackUstreamerConfig": ustreamerConfig(rec),
  };
}

export function peekDownloadJson(videoId: string): Record<string, unknown> | null {
  prune();
  const e = cache[videoId];
  if (!e || !complete(e) || e.expires < now()) return null;
  e.expires = now() + CACHE_MS;
  return buildDownloadJson(e);
}

export function waitDownloadJson(videoId: string, ms: number): Promise<Record<string, unknown>> {
  const hit = peekDownloadJson(videoId);
  if (hit) return Promise.resolve(hit);
  const e = entry(videoId);
  return new Promise(function (resolve, reject) {
    const t = setTimeout(function () {
      reject(new Error("player response not cached"));
    }, ms);
    e.waiters.push(function (ok) {
      clearTimeout(t);
      if (!ok) {
        reject(new Error("player response not cached"));
        return;
      }
      try {
        resolve(buildDownloadJson(e));
      } catch (err) {
        reject(err);
      }
    });
    if (complete(e)) flush(e, true);
  });
}

export function downloadWaitMs(): number {
  return WAIT_MS;
}

function queryParam(url: string, key: string): string {
  const m = new RegExp("[?&]" + key + "=([^&]*)").exec(url);
  if (!m) return "";
  try {
    return decodeURIComponent(m[1]!.replace(/\+/g, " "));
  } catch (_e) {
    return m[1]!;
  }
}

function streamIdOf(url: string): string {
  return queryParam(url, "id");
}

export function notePlayerResponse(rec: Record<string, unknown>): void {
  const vd = asRec(rec["videoDetails"]);
  const vid = vd && typeof vd["videoId"] === "string" ? (vd["videoId"] as string) : "";
  if (!vid) return;
  const st = asRec(rec["playabilityStatus"]);
  const ok = !st || st["status"] === "OK" || st["status"] == null;
  if (!ok) return;
  lastPlayerVid = vid;
  const sd = asRec(rec["streamingData"]);
  const listed =
    sd && typeof sd["serverAbrStreamingUrl"] === "string" ? sd["serverAbrStreamingUrl"] : "";
  const sid = listed ? streamIdOf(listed as string) : "";
  if (sid) streamToVid[sid] = vid;
  const e = entry(vid);
  e.rec = rec;
  e.expires = now() + CACHE_MS;
  console.info(
    "mse player-res /player vid=" +
      vid +
      " stream=" +
      (sid || "-") +
      " sabr=" +
      e.sabrUrls.length +
      " wait=" +
      e.waiters.length,
  );
  if (complete(e)) flush(e, true);
}

/** SABR `id=o-…` mapped from a prior /player. Empty if this stream is unknown. */
export function videoIdForSabrUrl(url: string): string {
  const sid = streamIdOf(url);
  return (sid && streamToVid[sid]) || "";
}

export function noteSabrPlaybackUrl(url: string): void {
  if (url.indexOf("/videoplayback") < 0) return;
  if (url.indexOf("sabr=") < 0) return;
  const sid = streamIdOf(url);
  const vid = (sid && streamToVid[sid]) || lastPlayerVid;
  if (!vid) return;
  if (sid && !streamToVid[sid]) streamToVid[sid] = vid;
  const e = entry(vid);
  if (e.sabrUrls.length && e.sabrUrls[e.sabrUrls.length - 1] === url) return;
  e.sabrUrls.push(url);
  e.expires = now() + CACHE_MS;
  debug(function () {
    return "mse player-res sabr vid=" + vid + " stream=" + (sid || "-") + " n=" + e.sabrUrls.length;
  });
  if (complete(e)) flush(e, true);
}
