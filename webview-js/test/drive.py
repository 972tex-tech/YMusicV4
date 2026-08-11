"""Drive the injected webview-js bundle in a throwaway Chrome (proxied through mitmproxy) over CDP:
open the embed page, call ymusicAction(load), and stream every [ymix] console event with timestamps
so we can watch for the ~43s failure. Chrome must already be running with --remote-debugging-port=9222 AND --mute-audio
(tests are muted). See run.sh, which launches it that way.

    drive.py [videoId] [watchSeconds] [mobile|desktop]

Default is `mobile`: a mobile UA + viewport + touch are emulated via CDP BEFORE navigation, so
YouTube serves the mobile embed player (the surface the Android WebView actually gets, and the one
that stopped at ~43s on-device) instead of the desktop one.
"""
import sys
import time

import pychrome

VIDEO_ID = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
WATCH_SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 75
PLATFORM = sys.argv[3] if len(sys.argv) > 3 else "mobile"

# A current Android Chrome, matching the emulated device (Pixel-class). Client-hints metadata is set
# too, since YouTube reads sec-ch-ua-mobile / platform to pick the mobile player.
MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)
MOBILE_UA_METADATA = {
    "platform": "Android",
    "platformVersion": "14",
    "architecture": "",
    "model": "Pixel 7 Pro",
    "mobile": True,
    "brands": [
        {"brand": "Chromium", "version": "150"},
        {"brand": "Google Chrome", "version": "150"},
        {"brand": "Not_A Brand", "version": "24"},
    ],
}

browser = pychrome.Browser(url="http://127.0.0.1:9222")
tab = browser.new_tab()

t0 = time.time()


def log(*a):
    print(f"[{time.time() - t0:6.1f}s]", *a, flush=True)


def on_console(**kwargs):
    entry = kwargs.get("entry", {})
    text = entry.get("text", "")
    if "ymix" in text or "ERROR" in entry.get("level", "").upper():
        log("CONSOLE", entry.get("level"), text[:400])


def on_console_api(**kwargs):
    args = kwargs.get("args", [])
    parts = [a.get("value", a.get("description", "")) for a in args]
    line = " ".join(str(p) for p in parts)
    if "[ymix" in line or "error" in kwargs.get("type", ""):
        log("LOG", line[:400])


tab.set_listener("Log.entryAdded", on_console)
tab.set_listener("Runtime.consoleAPICalled", on_console_api)
tab.start()
tab.call_method("Log.enable")
tab.call_method("Runtime.enable")
tab.call_method("Page.enable")

if PLATFORM == "mobile":
    log("emulating mobile (Pixel 7 Pro): UA + 412x915 viewport + touch")
    tab.call_method("Emulation.setUserAgentOverride", userAgent=MOBILE_UA, userAgentMetadata=MOBILE_UA_METADATA)
    tab.call_method(
        "Emulation.setDeviceMetricsOverride",
        width=412,
        height=915,
        deviceScaleFactor=2.625,
        mobile=True,
    )
    tab.call_method("Emulation.setTouchEmulationEnabled", enabled=True, maxTouchPoints=5)

url = f"https://www.youtube.com/embed/{VIDEO_ID}"
log(f"navigating ({PLATFORM})", url)
tab.call_method("Page.navigate", url=url)
time.sleep(8)

# Confirm the bundle installed the bridge + entrypoint.
probe = tab.call_method(
    "Runtime.evaluate",
    expression="({sid: window.ymusicSessionId, action: typeof window.ymusicAction, api: typeof window.ymixWvApi, mobileUA: /Mobile/.test(navigator.userAgent)})",
    returnByValue=True,
)
log("probe", probe.get("result", {}).get("value"))

# Load + play the video.
action = '{"what":0,"videoId":"%s","playWhenReady":true,"volume":1,"startTimeSeconds":0}' % VIDEO_ID
res = tab.call_method(
    "Runtime.evaluate",
    expression=f"window.ymusicAction && window.ymusicAction(JSON.stringify({action}))",
    returnByValue=True,
)
log("ymusicAction(load) ->", res.get("result", {}).get("value"))

log(f"watching console for {WATCH_SECONDS}s (looking for state_changed / report_error) ...")
end = time.time() + WATCH_SECONDS
while time.time() < end:
    time.sleep(1)

# Final state snapshot.
snap = tab.call_method(
    "Runtime.evaluate",
    expression="(function(){try{var p=document.querySelector('#movie_player');return p&&p.getPlayerState?('state='+p.getPlayerState()+' t='+(p.getCurrentTime&&p.getCurrentTime())):'no player';}catch(e){return ''+e;}})()",
    returnByValue=True,
)
log("final", snap.get("result", {}).get("value"))
tab.stop()
browser.close_tab(tab)
