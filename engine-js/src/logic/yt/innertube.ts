// InnerTube request plumbing (docs/innertube/README.md): every call is a POST with the WEB client
// context. The volatile params (clientVersion / visitorData / sts) come from the bootstrapped
// session (session.ts), not a hardcoded constant — so a feed-shape or client-version bump ships
// without an app release. A 403 (params rotated server-side) refreshes the session once and retries.

import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { DESKTOP_UA, SESSION_MAX_AGE_MS, effectiveLocale, getSession } from "./session";
import type { YtSession } from "./session";
import { executeWithCookies } from "./cookies";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";

const ORIGIN = "https://www.youtube.com";

/**
 * POST `youtubei/v1/<endpoint>` with `fields` merged next to the context. The hl/gl pair is the
 * ENGINE's own (the pick, else the session's server-echoed answer) — callers never assert one —
 * and the session supplies clientVersion + visitorData. A 403 is retried once
 * against a freshly-scraped session. The parsed body comes back via the streaming parser.
 */
export async function innertubeJson(
  endpoint: string,
  fields: Record<string, unknown>,
  log?: JsLogger,
): Promise<Record<string, unknown>> {
  const res = await innertubeResponse(endpoint, fields, log);
  if (!res.ok) {
    res.close();
    throw JsEngineErrorImpl.httpStatus("yt " + endpoint, res.status, "E20140");
  }
  return res.jsonObject();
}

/** [innertubeJson], except a 404 (YouTube knows no such thing) answers null. */
export async function innertubeJsonOrNotFound(
  endpoint: string,
  fields: Record<string, unknown>,
  log?: JsLogger,
): Promise<Record<string, unknown> | null> {
  const res = await innertubeResponse(endpoint, fields, log);
  if (res.status === 404) {
    res.close();
    return null;
  }
  if (!res.ok) {
    res.close();
    throw JsEngineErrorImpl.httpStatus("yt " + endpoint, res.status, "E20141");
  }
  return res.jsonObject();
}

async function innertubeResponse(
  endpoint: string,
  fields: Record<string, unknown>,
  log?: JsLogger,
): Promise<HttpResponse> {
  let session = await getSession(SESSION_MAX_AGE_MS);
  let res = await post(endpoint, fields, session, log);
  if (res.status === 403) {
    res.close();
    session = await getSession(0); // rotate: re-scrape the homepage, then retry once
    res = await post(endpoint, fields, session, log);
  }
  return res;
}

async function post(
  endpoint: string,
  fields: Record<string, unknown>,
  session: YtSession,
  log?: JsLogger,
): Promise<HttpResponse> {
  const locale = await effectiveLocale();
  const client: Record<string, unknown> = {
    "clientName": "WEB",
    "clientVersion": session.clientVersion,
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
    .setHeader("X-Youtube-Client-Name", session.clientName)
    .setHeader("X-Youtube-Client-Version", session.clientVersion)
    .setHeader("Origin", ORIGIN)
    .setHeader("X-Origin", ORIGIN)
    .setHeader("Referer", ORIGIN + "/")
    .setHeaderSanitized("X-Goog-Visitor-Id", session.visitorData)
    .logger((log ?? jsLogger("yt")).fork(endpoint));

  return executeWithCookies(url, builder, {
    sessionIndex: session.sessionIndex,
    delegatedSessionId: session.delegatedSessionId,
    userSessionId: session.userSessionId,
  });
}
