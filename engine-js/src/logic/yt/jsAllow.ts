/** Script GETs that may enter the player QuickJS. Everything else .js is 404.
 *  BotGuard interpreter (`/js/th/`), player `base.js`, and `heartbeat.js` (live). */
export function allowEngineJsUrl(url: string): boolean {
  const q = url.indexOf("?");
  const path = (q >= 0 ? url.slice(0, q) : url).toLowerCase();
  if (path.indexOf(".js") < 0) return true;
  if (path.indexOf("/js/th/") >= 0) return true;
  if (path.indexOf("botguard") >= 0) return true;
  if (path.indexOf("/s/player/") < 0) return false;
  return endsWith(path, "/base.js") || endsWith(path, "/heartbeat.js");
}

function endsWith(path: string, suffix: string): boolean {
  return path.length >= suffix.length && path.slice(path.length - suffix.length) === suffix;
}
