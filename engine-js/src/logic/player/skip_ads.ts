// Strip ads config from InnerTube /player JSON (webview-js yt/skip_ads.ts
// transformPlayerRequest / transformPlayerResponse). No PO inject — OWL mints.

import { utf8Decode, utf8Encode } from "../../transport";
import { setXhrJsonRewrite } from "../yt/xhr";

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

function wrapFetch(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  const orig = g["fetch"];
  if (typeof orig !== "function") return;
  async function wrapped(input: unknown, init?: unknown): Promise<unknown> {
    let url = "";
    const inRec = asRec(input);
    if (typeof input === "string") url = input;
    else if (inRec && inRec["url"] != null) url = String(inRec["url"]);
    if (!isPlayerUrl(url))
      return (orig as (a: unknown, b?: unknown) => Promise<unknown>)(input, init);
    const extra: Record<string, unknown> =
      init != null && typeof init === "object"
        ? Object.assign({}, init as Record<string, unknown>)
        : {};
    let body: unknown = extra["body"];
    if (body == null && inRec && inRec["body"] != null) body = inRec["body"];
    extra["body"] = rewriteRequest(url, body);
    if (inRec) {
      if (extra["method"] == null && inRec["method"] != null) extra["method"] = inRec["method"];
      if (extra["headers"] == null && inRec["headers"] != null) extra["headers"] = inRec["headers"];
      if (extra["signal"] == null && inRec["signal"] != null) extra["signal"] = inRec["signal"];
    }
    const res = (await (orig as (a: unknown, b?: unknown) => Promise<unknown>)(
      typeof input === "string" ? input : url,
      extra,
    )) as Record<string, unknown>;
    if (typeof res["text"] !== "function") return res;
    const text = await (res["text"] as () => Promise<string>)();
    const outText = rewriteResponse(url, text);
    const Resp = g["Response"] as (new (b?: unknown, i?: unknown) => unknown) | undefined;
    if (typeof Resp !== "function") return res;
    return new Resp(outText, {
      "status": res["status"],
      "statusText": res["statusText"],
      "headers": res["headers"],
    });
  }
  if (typeof __nativeFn === "function") g["fetch"] = __nativeFn("fetch", wrapped);
  else g["fetch"] = wrapped;
}

let installed = false;

export function installPlayerAdStrip(): void {
  if (installed) return;
  installed = true;
  wrapFetch();
  setXhrJsonRewrite(rewriteRequest, rewriteResponse);
}
