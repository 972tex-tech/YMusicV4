"""
mitmproxy addon that serves YouTube's /embed page with the webview-js bundle injected at
document-start and CSP stripped — so the WebView player engine can be driven from a desktop
browser, with no Android/iOS/JCEF host.

The engine is just a content script that rewrites YouTube's own player over
`window.ymusicAction(...)`. In production the native host evals dist/webview_ymusic.js before any
page script runs; here the proxy achieves the same by (1) making our bundle the FIRST <script> in
<head> and (2) removing the CSP headers that would otherwise block an injected inline/eval'd script.

Usage (see webview-js/README.md "Testing in a desktop browser"):
    npm run build                       # produce dist/webview_ymusic.js first
    mitmdump -s test/inject.py          # proxy on 127.0.0.1:8080
Point a browser at the proxy, trust the mitmproxy CA (http://mitm.it), open
    https://www.youtube.com/embed/<videoId>
then in the console:
    ymusicAction(JSON.stringify({ what: 0, videoId: '<id>', playWhenReady: true, volume: 1 }))
JS->native events print to the console as `[ymix] <channel> <payload>`.
"""

import re
from pathlib import Path

from mitmproxy import http

BUNDLE_FILE = Path(__file__).resolve().parent.parent / "dist" / "webview_ymusic.js"
# Served same-origin so no extra host/cert is involved; a path YouTube itself will never use.
BUNDLE_PATH = "/__ymusic_test_bundle.js"
SESSION_ID = "ymtest"

# Installed before the bundle: the runtime globals the host normally provides, plus a
# ymixChannelAndroid stub that routes every JS->native event to the console. Kept ES5 so it runs in
# the same old-WebView floor the bundle targets.
PRELUDE = (
    "window.ymusicSessionId=%r;var ymusicSessionId=window.ymusicSessionId;"
    "window.ymixInitConfig={platform:'android'};"
    "window.isAndroid=true;window.androidOsVer=34;window.mainAppVer=1;"
    "window.enablePlayerCache=false;"
    "window.ymixChannelAndroid={"
    "postMessage:function(c,m){console.log('[ymix]',c,m);},"
    "remoteDebug:function(s,p){console.log('[ymix:dbg]',s,p);}"
    "};"
    "console.log('[ymix] prelude ready \\u2014 ymusicAction(JSON.stringify({what:0,videoId:\"...\",playWhenReady:true}))');"
) % SESSION_ID

_CSP_HEADERS = (
    "content-security-policy",
    "content-security-policy-report-only",
    "x-content-security-policy",
    "x-webkit-csp",
)


def _read_bundle() -> str:
    try:
        return BUNDLE_FILE.read_text(encoding="utf-8")
    except FileNotFoundError:
        return "console.error('[ymix] dist/webview_ymusic.js missing \\u2014 run `npm run build`');"


def request(flow: http.HTTPFlow) -> None:
    # Short-circuit the sentinel URL with the freshly-read bundle (pick up `npm run watch` rebuilds
    # without restarting the proxy).
    if flow.request.host == "www.youtube.com" and flow.request.path == BUNDLE_PATH:
        flow.response = http.Response.make(
            200,
            _read_bundle().encode("utf-8"),
            {"Content-Type": "application/javascript; charset=utf-8", "Cache-Control": "no-store"},
        )


def response(flow: http.HTTPFlow) -> None:
    if flow.response is None:
        return
    # Strip CSP on every YouTube response so the injected script and its script-evals are allowed.
    if flow.request.host.endswith("youtube.com"):
        for name in _CSP_HEADERS:
            flow.response.headers.pop(name, None)

    if flow.request.host != "www.youtube.com" or not flow.request.path.startswith("/embed/"):
        return
    ctype = flow.response.headers.get("content-type", "")
    if "text/html" not in ctype:
        return

    html = flow.response.get_text()
    if not html or "<head" not in html:
        return
    inject = f"<script>{PRELUDE}</script><script src=\"{BUNDLE_PATH}\"></script>"
    # Make our scripts the first children of <head>, so the bundle runs before YouTube's own scripts.
    flow.response.set_text(re.sub(r"(<head[^>]*>)", lambda m: m.group(1) + inject, html, count=1))
