// yt/embed — the WebView player's embed page, served by the engine so (a) the fetch rides the
// cooked cookie identity every other YouTube request shares (cookies.ts), and (b) the HTML rewrite
// lives HERE, remotely updatable: a YouTube change to the embed page's shape is a bundle push, not
// an app release. The one identity exception: the request carries the CALLER's User-Agent verbatim
// (the WebView's own UA, never DESKTOP_UA) — the page must look like it was loaded by the WebView
// that will run it, or googlevideo's attestation rejects playback mid-stream.
//
// The result HTML is NOT final: the boot script's slot is the INJECT_JS_HERE placeholder (inside a
// <script> tag, first in <head>), which the app replaces with its own boot script — session globals
// plus the webview_ymusic.js bundle. The bundle text stays an app asset and never crosses the pipe.
//
//   yt/embed  JS<-K {"userAgent":…}   K<-JS {"html":"<!doctype …INJECT_JS_HERE…"}

import { onAccept } from "../../transport";
import { hostLogger } from "../../protocol/logger";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { executeWithCookies } from "./cookies";
import { asString } from "./scan";

const EMBED_URL = "https://www.youtube.com/embed";
const INJECT_PLACEHOLDER = "INJECT_JS_HERE";

// The rewrites, ported from the legacy WebViewYtPlayer.shouldInterceptRequest splice (previously
// the app-side HtmlInjector). Order matters: strip CSP and defang preloads BEFORE inserting the
// script slot, so the inserted tag is never itself rewritten.
const RE_CSP_META = /(<meta[^>]*http-equiv=["'])content-security-policy(["'])/gi;
const RE_SCRIPT_PRELOAD = /(<link\b[^>]*\bas=["'])script(["'])/gi;
const RE_HEAD_OPEN = /<head\b[^>]*>/i;
const RE_BODY_OPEN = /<body\b[^>]*>/i;

/**
 * Returns `html` with the CSP meta neutralized, script preloads defanged, and the placeholder
 * script tag inserted as the first child of `<head>` (fallback `<body>`, then plain prepend) —
 * so the app's spliced-in boot script runs at document-start, before any YouTube script.
 */
export function prepareEmbedHtml(html: string): string {
  const out = html
    .replace(RE_CSP_META, "$1content-security-policy-disabled$2")
    .replace(RE_SCRIPT_PRELOAD, "$1script-blocked$2");
  const tag = "<script>\n" + INJECT_PLACEHOLDER + "\n</script>";
  const head = RE_HEAD_OPEN.exec(out);
  if (head !== null) {
    const at = head.index + head[0].length;
    return out.slice(0, at) + tag + out.slice(at);
  }
  const body = RE_BODY_OPEN.exec(out);
  if (body !== null) {
    const at = body.index + body[0].length;
    return out.slice(0, at) + tag + out.slice(at);
  }
  return tag + out;
}

onAccept(
  "yt/embed",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const userAgent = asString(args["userAgent"]);
    if (userAgent === undefined) throw JsEngineErrorImpl.parse("yt/embed needs userAgent");
    const res = await executeWithCookies(
      EMBED_URL,
      HttpRequest.get(EMBED_URL)
        .setHeader("User-Agent", userAgent)
        .setHeader("Accept", "text/html")
        .logger(hostLogger(args, "yt/embed")),
    );
    if (!res.ok) {
      res.close();
      throw JsEngineErrorImpl.network("yt embed HTTP " + res.status);
    }
    const html = prepareEmbedHtml(await res.text());
    const p = out.writeObjectAsJson({ "html": html });
    if (p) await p;
  }),
);
