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

const FAKE_ITAG = 10;
const FAKE_MIME = 'video/mp4; codecs="av01.0.00M.08.fake"';

function isVideoMp4(fmt: Record<string, unknown>): boolean {
  const mime = String(fmt["mimeType"] || "");
  return mime.indexOf("video/mp4") === 0;
}

/** Lowest video/mp4 in adaptiveFormats (height, then bitrate). */
function lowestVideoMp4(formats: unknown[]): Record<string, unknown> | null {
  let best: Record<string, unknown> | null = null;
  let bestH = Infinity;
  let bestBr = Infinity;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt || !isVideoMp4(fmt)) continue;
    const h = Number(fmt["height"]);
    const br = Number(fmt["bitrate"] != null ? fmt["bitrate"] : fmt["averageBitrate"]);
    const height = isFinite(h) && h > 0 ? h : Infinity;
    const bitrate = isFinite(br) && br > 0 ? br : Infinity;
    if (height < bestH || (height === bestH && bitrate < bestBr)) {
      best = fmt;
      bestH = height;
      bestBr = bitrate;
    }
  }
  return best;
}

/**
 * Clone the lowest video/mp4 adaptiveFormat as itag 10.
 * initRange/indexRange are inclusive byte indexes (YouTube 0–699 = 700 bytes).
 */
function injectFakeMp4Format(rec: Record<string, unknown>): void {
  const sd = asRec(rec["streamingData"]);
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (fmt && Number(fmt["itag"]) === FAKE_ITAG) return;
  }
  const src = lowestVideoMp4(formats);
  if (!src) {
    console.log("mse fake-fmt skip: no video/mp4");
    return;
  }
  const fake = asRec(JSON.parse(JSON.stringify(src)));
  if (!fake) return;
  fake["itag"] = FAKE_ITAG;
  fake["mimeType"] = FAKE_MIME;
  fake["initRange"] = { "start": "0", "end": "1" };
  fake["indexRange"] = { "start": "2", "end": "3" };
  fake["width"] = 50;
  fake["height"] = 50;
  fake["contentLength"] = "100";
  fake["lastModified"] = "1770737429196304";
  fake["quality"] = "tiny";
  fake["qualityLabel"] = "100p";
  fake["qualityOrdinal"] = "QUALITY_ORDINAL_UNKNOWN";
  formats.push(fake);
  console.log(
    "mse fake-fmt itag=10 from=" +
      String(src["itag"]) +
      " " +
      String(src["qualityLabel"] || "") +
      " -> 100p",
  );
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
