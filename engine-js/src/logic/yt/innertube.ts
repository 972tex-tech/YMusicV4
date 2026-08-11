// InnerTube request plumbing (docs/innertube/README.md): every call is a POST with the WEB client
// context. The volatile params (clientVersion / visitorData / sts) come from the bootstrapped
// session (session.ts), not a hardcoded constant — so a feed-shape or client-version bump ships
// without an app release. A 403 (params rotated server-side) refreshes the session once and retries.

import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { DESKTOP_UA, effectiveLocale, getSession } from "./session";
import type { YtSession } from "./session";
import { executeWithCookies } from "./cookies";

const ORIGIN = "https://www.youtube.com";

/**
 * POST `youtubei/v1/<endpoint>` with `fields` merged next to the context. The hl/gl pair is the
 * ENGINE's own (the pick, else the session's server-echoed answer) — callers never assert one —
 * and the session supplies clientVersion + visitorData. Non-2xx throws classified "network"; a 403 is retried once
 * against a freshly-scraped session. The parsed body comes back via the streaming parser.
 */
export async function innertubeJson(
  endpoint: string,
  fields: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  let session = await getSession(false);
  let res = await post(endpoint, fields, session);
  if (res.status === 403) {
    res.ignoreResult();
    session = await getSession(true); // rotate: re-scrape the homepage, then retry once
    res = await post(endpoint, fields, session);
  }
  if (!res.ok) {
    res.ignoreResult();
    throw JsEngineErrorImpl.network("yt " + endpoint + " HTTP " + res.status);
  }
  return res.jsonObject();
}

async function post(
  endpoint: string,
  fields: Record<string, unknown>,
  session: YtSession,
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
    .logContext("yt/" + endpoint);

  return executeWithCookies(url, builder, {
    sessionIndex: session.sessionIndex,
    delegatedSessionId: session.delegatedSessionId,
    userSessionId: session.userSessionId,
  });
}
