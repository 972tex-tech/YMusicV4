// A cacheable SABR /videoplayback carries the /player adaptiveFormats catalog as X-Ym-Af (base64url
// JSON array). The host routes only requests with it through the media cache, and strips it before
// googlevideo. Cacheable: SABR, not a probe, not live.

import { utf8Encode } from "../../transport";
import { addInterceptor } from "../../protocol/http/intercept";
import type { HttpChain } from "../../protocol/http/intercept";
import { sabrAdaptiveFormats } from "../player/player_res";
import { b64url } from "./sabr_log";

const HEADER = "X-Ym-Af";

function canCacheSabr(url: string): boolean {
  if (url.indexOf("/videoplayback") < 0 || url.indexOf("sabr=1") < 0) return false;
  if (url.indexOf("probe=1") >= 0) return false;
  return (
    url.indexOf("live=1") < 0 && url.indexOf("yt_live_broadcast") < 0 && url.indexOf("noclen=1") < 0
  );
}

function adaptiveFormatsIntercept(chain: HttpChain): Promise<HttpResponse> {
  const req = chain.request;
  if (!canCacheSabr(req.url)) return chain.proceed();
  const catalog = sabrAdaptiveFormats(req.url);
  if (!catalog.length) return chain.proceed();
  const headers = req.headers.slice();
  headers.push([HEADER, b64url(utf8Encode(JSON.stringify(catalog)))]);
  return chain.proceed(Object.assign({}, req, { headers: headers }));
}

let installed = false;

export function installAdaptiveFormatsHeader(): void {
  if (installed) return;
  installed = true;
  addInterceptor(adaptiveFormatsIntercept);
}
