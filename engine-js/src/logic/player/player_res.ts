// Cache /player JSON (pre–fake-144p) plus decoded SABR URLs captured from the
// player's own googlevideo requests. Download uses that; we do not decipher.

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

function buildDownloadJson(e: Entry): Record<string, unknown> {
  const rec = e.rec!;
  const sd = asRec(rec["streamingData"]) || {};
  const streaming: Record<string, unknown> = {};
  for (const k in sd) streaming[k] = sd[k];
  if (e.sabrUrls.length) streaming["serverAbrStreamingUrl"] = e.sabrUrls[e.sabrUrls.length - 1];
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

export function notePlayerResponse(rec: Record<string, unknown>): void {
  const vd = asRec(rec["videoDetails"]);
  const vid = vd && typeof vd["videoId"] === "string" ? (vd["videoId"] as string) : "";
  if (!vid) return;
  const st = asRec(rec["playabilityStatus"]);
  const ok = !st || st["status"] === "OK" || st["status"] == null;
  if (!ok) return;
  lastPlayerVid = vid;
  const e = entry(vid);
  e.rec = rec;
  e.expires = now() + CACHE_MS;
  console.log(
    "mse player-res /player vid=" +
      vid +
      " sabr=" +
      e.sabrUrls.length +
      " wait=" +
      e.waiters.length,
  );
  if (complete(e)) flush(e, true);
}

function videoIdFromUrl(url: string): string {
  const m = /[?&]v=([a-zA-Z0-9_-]{11})(?:&|$)/.exec(url);
  return m ? m[1]! : "";
}

export function noteSabrPlaybackUrl(url: string): void {
  if (url.indexOf("/videoplayback") < 0) return;
  if (url.indexOf("sabr=") < 0) return;
  const vid = videoIdFromUrl(url) || lastPlayerVid;
  if (!vid) return;
  const e = entry(vid);
  if (e.sabrUrls.length && e.sabrUrls[e.sabrUrls.length - 1] === url) return;
  e.sabrUrls.push(url);
  e.expires = now() + CACHE_MS;
  console.log("mse player-res sabr vid=" + vid + " n=" + e.sabrUrls.length);
  if (complete(e)) flush(e, true);
}
