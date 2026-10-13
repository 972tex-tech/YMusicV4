// Strip ads config from InnerTube /player JSON. No PO inject — OWL mints.
// Hooked as an HttpCall interceptor so fetch/XHR stay thin HttpRequest wrappers.

import { stringifyJson, utf8Decode } from "../../transport";
import { addInterceptor } from "../../protocol/http/intercept";
import type { HttpChain, HttpReq } from "../../protocol/http/intercept";
import { BufferedResponse } from "../../protocol/http/intercept";
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
  if (body == null || typeof body === "function") return null;
  if (typeof body === "string") s = body;
  else if (body instanceof Uint8Array) s = utf8Decode(body);
  else s = String(body);
  try {
    return asRec(JSON.parse(s));
  } catch (e) {
    console.warn("player ads: JSON parse failed: " + (e instanceof Error ? e.message : String(e)));
    return null;
  }
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

export { isPlayerUrl };

function rewriteReq(req: HttpReq): HttpReq {
  const rec = jsonRec(req.body);
  if (!rec) return req;
  try {
    transformPlayerRequest(rec);
  } catch (e) {
    console.warn("player ads: transformPlayerRequest failed:", e);
    return req;
  }
  const headers = req.headers.filter(function (e) {
    return String(e[0]).toLowerCase() !== "content-length";
  });
  return Object.assign({}, req, {
    headers: headers,
    body: function (io: PipeSink) {
      return io.writeObjectAsJson(rec);
    },
  });
}

async function playerAdsIntercept(chain: HttpChain): Promise<HttpResponse> {
  let req = chain.request;
  if (!isPlayerUrl(req.url)) return chain.proceed();
  req = rewriteReq(req);
  const res = await chain.proceed(req);
  const rec = await res.jsonObject();
  try {
    transformPlayerResponse(rec);
  } catch (e) {
    console.warn("player ads: transformPlayerResponse failed:", e);
  }
  return new BufferedResponse(res, stringifyJson(rec));
}

let installed = false;

export function installPlayerAdStrip(): void {
  if (installed) return;
  installed = true;
  addInterceptor(playerAdsIntercept);
}
