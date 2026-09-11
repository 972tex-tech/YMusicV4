// Strip ads config from InnerTube /player JSON (webview-js yt/skip_ads.ts
// transformPlayerRequest / transformPlayerResponse). No PO inject — OWL mints.

import { utf8Decode, utf8Encode } from "../../transport";
import { setXhrJsonRewrite } from "../yt/xhr";
import { ingestPlayerCaptions } from "./captions";
import { is144pItag, recordFake144 } from "./itag_144";
import type { Fake144Row } from "./itag_144";
import { notePlayerResponse } from "./player_res";

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

function isVideoFmt(fmt: Record<string, unknown>): boolean {
  const mime = String(fmt["mimeType"] || "");
  return mime.indexOf("video/") === 0;
}

/** Keep container; mark codec so SourceBuffer.changeType is visible. */
function fakeMime(mime: string): string {
  const m = String(mime);
  const key = 'codecs="';
  const i = m.indexOf(key);
  if (i < 0) return m;
  const start = i + key.length;
  const end = m.indexOf('"', start);
  if (end < 0) return m;
  const codec = m.slice(start, end);
  if (codec.indexOf(".fake") >= 0) return m;
  return m.slice(0, end) + ".fake" + m.slice(end);
}

/**
 * Rewrite real 144p adaptiveFormats in place (keep itag so Qf still maps qe).
 * initRange/indexRange are inclusive byte indexes (YouTube 0–699 = 700 bytes).
 */
function injectFakeMp4Format(rec: Record<string, unknown>): void {
  const sd = asRec(rec["streamingData"]);
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return;
  const itags: string[] = [];
  const rows: Fake144Row[] = [];
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt || !isVideoFmt(fmt) || !is144pItag(Number(fmt["itag"]))) continue;
    const itag = Number(fmt["itag"]);
    const lmt = String(fmt["lastModified"] || "0");
    if (String(fmt["mimeType"] || "").indexOf(".fake") < 0) {
      if (fmt["_lastModified"] == null) fmt["_lastModified"] = fmt["lastModified"];
      if (fmt["_mimeType"] == null) fmt["_mimeType"] = fmt["mimeType"];
      if (fmt["_xtags"] == null) fmt["_xtags"] = fmt["xtags"];
      if (fmt["_xtag"] == null) fmt["_xtag"] = fmt["xtag"];
      if (fmt["_initRange"] == null) fmt["_initRange"] = fmt["initRange"];
      if (fmt["_indexRange"] == null) fmt["_indexRange"] = fmt["indexRange"];
      fmt["mimeType"] = fakeMime(String(fmt["mimeType"] || ""));
      fmt["xtags"] = null;
      fmt["xtag"] = null;
      fmt["initRange"] = { "start": "0", "end": "1" };
      fmt["indexRange"] = { "start": "2", "end": "3" };
    }
    itags.push(String(itag));
    rows.push({
      itag: itag,
      mime: String(fmt["mimeType"] || ""),
      lmt: lmt,
      initStart: 0,
      initEnd: 1,
      indexStart: 2,
      indexEnd: 3,
    });
  }
  const vd = asRec(rec["videoDetails"]);
  const vid = vd ? String(vd["videoId"] || "") : "";
  recordFake144(vid, rows);
  if (itags.length)
    console.log(
      "mse fake-fmt 144p itags=" + itags.join(",") + " lmt=" + (rows[0] ? rows[0].lmt : "-"),
    );
  else console.log("mse fake-fmt skip: no 144p video");
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
  notePlayerResponse(rec);
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
  return JSON.stringify(rec);
}

let installed = false;

export function installPlayerAdStrip(): void {
  if (installed) return;
  installed = true;
  setXhrJsonRewrite(rewriteRequest, rewriteResponse);
}
