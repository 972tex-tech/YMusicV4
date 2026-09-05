// Strip ads config from InnerTube /player JSON (webview-js yt/skip_ads.ts
// transformPlayerRequest / transformPlayerResponse). No PO inject — OWL mints.

import { utf8Decode, utf8Encode } from "../../transport";
import { setXhrJsonRewrite } from "../yt/xhr";
import { ingestPlayerCaptions } from "./captions";
import { ingestPlayerLive } from "./live";

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

export function transformPlayerResponse(rec: Record<string, unknown>): Record<string, unknown> {
  delete rec["adPlacements"];
  delete rec["adSlots"];
  delete rec["playerAds"];
  rec["annotations"] = [];
  delete rec["endscreen"];
  const pc = asRec(rec["playerConfig"]);
  const dai = pc ? asRec(pc["daiConfig"]) : null;
  if (dai) dai["sendSsdaiMissingAdBreakReasons"] = false;
  ingestPlayerCaptions(rec);
  ingestPlayerLive(rec);
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
