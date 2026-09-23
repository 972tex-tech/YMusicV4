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
    console.warn("player ads: JSON parse failed: " + (e instanceof Error ? e.message : String(e)));
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

/** Snapshot 144p itag+lmt+mime from /player. Do not rewrite mime or byte ranges. */
function record144pFormats(rec: Record<string, unknown>): void {
  const sd = asRec(rec["streamingData"]);
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return;
  const itags: string[] = [];
  const rows: Fake144Row[] = [];
  let approxMs = 0;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt || !isVideoFmt(fmt) || !is144pItag(Number(fmt["itag"]))) continue;
    const itag = Number(fmt["itag"]);
    const lmt = String(fmt["lastModified"] || "0");
    if (!approxMs) {
      const approx = Number(fmt["approxDurationMs"]);
      if (isFinite(approx) && approx > 0) approxMs = Math.round(approx);
    }
    const initR = asRec(fmt["initRange"]);
    const indexR = asRec(fmt["indexRange"]);
    const mime = String(fmt["mimeType"] || "");
    const br = Number(fmt["bitrate"] || fmt["averageBitrate"] || 0);
    itags.push(String(itag));
    rows.push({
      itag: itag,
      mime: mime,
      lmt: lmt,
      initStart: initR ? Number(initR["start"]) || 0 : 0,
      initEnd: initR ? Number(initR["end"]) || 0 : 0,
      indexStart: indexR ? Number(indexR["start"]) || 0 : 0,
      indexEnd: indexR ? Number(indexR["end"]) || 0 : 0,
      bitrate: isFinite(br) && br > 0 ? Math.round(br) : 0,
      timescale: mime.indexOf("mp4") >= 0 ? 15360 : 1000,
    });
  }
  const vd = asRec(rec["videoDetails"]);
  const vid = vd ? String(vd["videoId"] || "") : "";
  let durationMs = approxMs;
  if (!durationMs && vd) {
    const sec = Number(vd["lengthSeconds"]);
    if (isFinite(sec) && sec > 0) durationMs = Math.round(sec * 1000);
  }
  const live = !!vd && vd["isLive"] === true;
  recordFake144(vid, rows, durationMs, live);
  if (itags.length)
    console.info(
      "mse fake-fmt 144p itags=" + itags.join(",") + " lmt=" + (rows[0] ? rows[0].lmt : "-"),
    );
  else console.info("mse fake-fmt skip: no 144p video");
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
  record144pFormats(rec);
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
    console.warn("player ads: transformPlayerRequest failed: " + errText(e));
    return body;
  }
  const s = JSON.stringify(rec);
  if (body instanceof Uint8Array) return utf8Encode(s);
  return s;
}

function rewriteResponse(url: string, rec: Record<string, unknown>): Record<string, unknown> {
  if (!isPlayerUrl(url)) return rec;
  try {
    transformPlayerResponse(rec);
  } catch (e) {
    console.warn("player ads: transformPlayerResponse failed: " + errText(e));
  }
  return rec;
}

let installed = false;

export function installPlayerAdStrip(): void {
  if (installed) return;
  installed = true;
  setXhrJsonRewrite(rewriteRequest, rewriteResponse);
}
