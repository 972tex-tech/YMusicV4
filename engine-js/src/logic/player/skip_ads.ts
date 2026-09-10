// Strip ads config from InnerTube /player JSON (webview-js yt/skip_ads.ts
// transformPlayerRequest / transformPlayerResponse). No PO inject — OWL mints.

import { utf8Decode, utf8Encode } from "../../transport";
import { setXhrJsonRewrite } from "../yt/xhr";
import { ingestPlayerCaptions } from "./captions";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

/** `/youtubei/v1/player` only. `/player/heartbeat` is live and must not get the ads rewrite. */
function isPlayerUrl(url: string): boolean {
  const q = url.indexOf("?");
  const path = q >= 0 ? url.slice(0, q) : url;
  return path.length >= 19 && path.slice(path.length - 19) === "/youtubei/v1/player";
}

function jsonRec(body: unknown): Record<string, unknown> | null {
  let s: string;
  if (typeof body === "string") s = body;
  else if (body instanceof Uint8Array) s = utf8Decode(body);
  else if (body == null) return null;
  else s = String(body);
  try {
    return asRec(JSON.parse(s));
  } catch (e) {
    console.log("player ads: JSON parse failed: " + (e instanceof Error ? e.message : String(e)));
    return null;
  }
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export function transformPlayerRequest(rec: Record<string, unknown>): Record<string, unknown> {
  rec["racyCheckOk"] = true;
  rec["contentCheckOk"] = true;
  /* Do not set isInlinePlaybackNoAd. Chrome watch/detailpage does not. */
  delete rec["serializedThirdPartyEmbedConfig"];
  return rec;
}

const FAKE_LMT = "1770737429196304";

function isVideoFmt(fmt: Record<string, unknown>): boolean {
  const mime = String(fmt["mimeType"] || "");
  return mime.indexOf("video/") === 0;
}

/** Prefer itag 399 (1080p AV1); else any video/mp4; else any video. */
function cloneSrc(formats: unknown[]): Record<string, unknown> | null {
  let mp4: Record<string, unknown> | null = null;
  let any: Record<string, unknown> | null = null;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt || !isVideoFmt(fmt)) continue;
    if (Number(fmt["itag"]) === 399) return fmt;
    const mime = String(fmt["mimeType"] || "");
    if (!mp4 && mime.indexOf("video/mp4") === 0) mp4 = fmt;
    if (!any) any = fmt;
  }
  return mp4 || any;
}

function durationMs(formats: unknown[], src: Record<string, unknown>): string {
  const own = src["approxDurationMs"];
  if (own != null && String(own) !== "") return String(own);
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    const d = fmt ? fmt["approxDurationMs"] : null;
    if (d != null && String(d) !== "") return String(d);
  }
  return "0";
}

type FakeSpec = { itag: number; mime: string };

/** itag 8=AV1, 9=avc1, 10=VP9. Codec strings are fake so SABR will not fetch them. */
const FAKE_SPECS: FakeSpec[] = [
  { itag: 8, mime: 'video/mp4; codecs="av01.0.00M.08.fake"' },
  { itag: 9, mime: 'video/mp4; codecs="avc1.42c00a.fake"' },
  { itag: 10, mime: 'video/webm; codecs="vp9.fake"' },
];

/**
 * Clone a real adaptiveFormat into three tiny 144p rows (itags 8/9/10).
 * initRange/indexRange are inclusive byte indexes (YouTube 0–699 = 700 bytes).
 */
function injectFakeMp4Format(rec: Record<string, unknown>): void {
  const sd = asRec(rec["streamingData"]);
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (fmt && Number(fmt["itag"]) === 8) return;
  }
  const src = cloneSrc(formats);
  if (!src) {
    console.log("mse fake-fmt skip: no video format");
    return;
  }
  const dur = durationMs(formats, src);
  const from = String(src["itag"]);
  for (let i = 0; i < FAKE_SPECS.length; i++) {
    const spec = FAKE_SPECS[i]!;
    const fake = asRec(JSON.parse(JSON.stringify(src)));
    if (!fake) continue;
    fake["itag"] = spec.itag;
    fake["mimeType"] = spec.mime;
    fake["initRange"] = { "start": "0", "end": "1" };
    fake["indexRange"] = { "start": "2", "end": "3" };
    fake["width"] = 50;
    fake["height"] = 50;
    fake["fps"] = 30;
    fake["bitrate"] = 1;
    fake["averageBitrate"] = 1;
    fake["contentLength"] = "100";
    fake["lastModified"] = FAKE_LMT;
    fake["quality"] = "tiny";
    fake["qualityLabel"] = "144p";
    fake["qualityOrdinal"] = "QUALITY_ORDINAL_UNKNOWN";
    fake["approxDurationMs"] = dur;
    formats.push(fake);
  }
  console.log("mse fake-fmt itags=8,9,10 from=" + from + " dur=" + dur);
}

export function transformPlayerResponse(rec: Record<string, unknown>): Record<string, unknown> {
  delete rec["adPlacements"];
  delete rec["adSlots"];
  delete rec["playerAds"];
  rec["annotations"] = [];
  delete rec["endscreen"];
  const pc = asRec(rec["playerConfig"]);
  const dai = pc ? asRec(pc["daiConfig"]) : null;
  if (dai) dai["sendSsdaiMissingAdBreakReasons"] = false;
  injectFakeMp4Format(rec);
  ingestPlayerCaptions(rec);
  return rec;
}

function rewriteRequest(url: string, body: unknown): unknown {
  if (!isPlayerUrl(url)) return body;
  const rec = jsonRec(body);
  if (!rec) return body;
  try {
    transformPlayerRequest(rec);
  } catch (e) {
    console.log("player ads: transformPlayerRequest failed: " + errText(e));
    return body;
  }
  const s = JSON.stringify(rec);
  if (body instanceof Uint8Array) return utf8Encode(s);
  return s;
}

function rewriteResponse(url: string, text: string): string {
  if (!isPlayerUrl(url)) return text;
  const rec = jsonRec(text);
  if (!rec) return text;
  try {
    transformPlayerResponse(rec);
  } catch (e) {
    console.log("player ads: transformPlayerResponse failed: " + errText(e));
    return text;
  }
  const out = JSON.stringify(rec);
  lastPlayerJson = out;
  return out;
}

let lastPlayerJson = "";

export function lastPlayerResponseJson(): string {
  return lastPlayerJson;
}

let installed = false;

export function installPlayerAdStrip(): void {
  if (installed) return;
  installed = true;
  setXhrJsonRewrite(rewriteRequest, rewriteResponse);
}
