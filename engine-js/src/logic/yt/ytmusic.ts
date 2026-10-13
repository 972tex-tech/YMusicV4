// music.youtube.com (WEB_REMIX) request plumbing — the music-catalog client (docs/innertube/ytmusic.md).
// Same youtubei/v1 endpoints and cookie identity as the WEB client (innertube.ts), but a different
// clientName/version and the music.youtube.com origin, so music home/explore/search come back as the
// music* renderers (musicParse.ts) rather than the WEB feed shapes. Streams still resolve through the
// WEB /player via the shared videoId — WEB_REMIX /player is UNPLAYABLE (see the doc's player warning).

import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { DESKTOP_UA, SESSION_MAX_AGE_MS, effectiveLocale, getSession } from "./session";
import type { YtSession } from "./session";
import { executeWithCookies } from "./cookies";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";

const ORIGIN = "https://music.youtube.com";
// The WEB_REMIX identity (docs/innertube/ytmusic.md, verified 2026-07-11). Its clientVersion goes
// stale like the WEB one, but music.youtube.com's ytcfg is NOT part of the scraped WEB session — so
// it's a constant here, updatable with the remote bundle. visitorData and cookies are client-agnostic
// (they follow the connection/identity, not the client), so the WEB session's own are reused.
const MUSIC_CLIENT_NAME = "67";
const MUSIC_CLIENT_VERSION = "1.20260707.12.00";

/**
 * POST `youtubei/v1/<endpoint>` on music.youtube.com with the WEB_REMIX context and `fields` merged
 * next to it. Mirrors innertube.ts: the session supplies visitorData, the engine owns hl/gl, a 403
 * refreshes the session once and retries.
 */
export async function musicJson(
  endpoint: string,
  fields: Record<string, unknown>,
  log?: JsLogger,
): Promise<Record<string, unknown>> {
  let session = await getSession(SESSION_MAX_AGE_MS);
  let res = await post(endpoint, fields, session, log);
  if (res.status === 403) {
    res.close();
    session = await getSession(0); // rotate: re-scrape, retry once
    res = await post(endpoint, fields, session, log);
  }
  if (!res.ok) {
    res.close();
    throw JsEngineErrorImpl.httpStatus("ytmusic " + endpoint, res.status, "E20142");
  }
  return res.jsonObject();
}

async function post(
  endpoint: string,
  fields: Record<string, unknown>,
  session: YtSession,
  log?: JsLogger,
): Promise<HttpResponse> {
  const locale = await effectiveLocale();
  const client: Record<string, unknown> = {
    "clientName": "WEB_REMIX",
    "clientVersion": MUSIC_CLIENT_VERSION,
    "hl": locale.hl,
    "gl": locale.gl,
    "platform": "DESKTOP",
    "originalUrl": ORIGIN + "/",
    "userAgent": DESKTOP_UA + ",gzip(gfe)",
    "visitorData": session.visitorData,
  };
  const body: Record<string, unknown> = {
    "context": { "client": client, "request": { "useSsl": true } },
  };
  for (const k in fields) body[k] = fields[k];

  const url = ORIGIN + "/youtubei/v1/" + endpoint + "?prettyPrint=false";
  const builder = HttpRequest.post(url)
    .json(body)
    .setHeader("User-Agent", DESKTOP_UA)
    .setHeader("X-Youtube-Client-Name", MUSIC_CLIENT_NAME)
    .setHeader("X-Youtube-Client-Version", MUSIC_CLIENT_VERSION)
    .setHeader("Origin", ORIGIN)
    .setHeader("X-Origin", ORIGIN)
    .setHeader("Referer", ORIGIN + "/")
    .setHeaderSanitized("X-Goog-Visitor-Id", session.visitorData)
    .logger((log ?? jsLogger("ytmusic")).fork(endpoint));

  return executeWithCookies(url, builder, {
    sessionIndex: session.sessionIndex,
    delegatedSessionId: session.delegatedSessionId,
    userSessionId: session.userSessionId,
  });
}
