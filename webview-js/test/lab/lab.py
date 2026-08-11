"""Audio-only switch lab: drive the injected bundle in throwaway Chrome via CDP with MSE
instrumentation + per-track network byte accounting, and run a scripted scenario:

    load -> play PHASE1 s -> quality 'zero' -> PHASE2 s -> quality back -> PHASE3 s

Prints an interleaved timeline of [ymix] events, [mse] ops, network per-mime byte counters and a
1 Hz playback snapshot. Requires mitmdump -s test/inject.py on :8080 and Chrome CDP on :9223.

    lab.py <videoId> [mobile|desktop] [phase1] [phase2] [phase3] [backQuality]
"""
import json
import re
import sys
import time
import threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pychrome

VIDEO_ID = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
PLATFORM = sys.argv[2] if len(sys.argv) > 2 else "mobile"
PHASE1 = int(sys.argv[3]) if len(sys.argv) > 3 else 18
PHASE2 = int(sys.argv[4]) if len(sys.argv) > 4 else 25
PHASE3 = int(sys.argv[5]) if len(sys.argv) > 5 else 25
BACK_QUALITY = sys.argv[6] if len(sys.argv) > 6 else "hd720"

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)
MOBILE_UA_METADATA = {
    "platform": "Android", "platformVersion": "14", "architecture": "",
    "model": "Pixel 7 Pro", "mobile": True,
    "brands": [
        {"brand": "Chromium", "version": "150"},
        {"brand": "Google Chrome", "version": "150"},
        {"brand": "Not_A Brand", "version": "24"},
    ],
}

MSE_PROBE = (Path(__file__).parent / "mse_probe.js").read_text()

browser = pychrome.Browser(url="http://127.0.0.1:9223")
tab = browser.new_tab()
t0 = time.time()
lock = threading.Lock()

def log(*a):
    with lock:
        print(f"[{time.time() - t0:7.1f}s]", *a, flush=True)

# ---- network accounting -------------------------------------------------------------------
req_kind = {}          # requestId -> 'video' | 'audio' | 'vp-other' | None
counters = {"video": 0, "audio": 0, "vp-other": 0}
phase_name = ["init"]  # mutable current phase label
phase_counters = {}    # phase -> {kind: bytes}

def classify(url):
    if "videoplayback" not in url and "ymusicForward" not in url:
        return None
    q = parse_qs(urlparse(url).query)
    mime = (q.get("mime") or [""])[0]
    if mime.startswith("video/"):
        return "video"
    if mime.startswith("audio/"):
        return "audio"
    return "vp-other"

def on_request(**kw):
    url = kw.get("request", {}).get("url", "")
    kind = classify(url)
    if kind:
        req_kind[kw.get("requestId")] = kind
        rng = re.search(r"[?&]range=([\d-]+)", url)
        if kind != "vp-other":
            log(f"NET req {kind} range={rng.group(1) if rng else '?'}")
        else:
            log(f"NET req {kind} {url[:120]}")

def on_finished(**kw):
    rid = kw.get("requestId")
    kind = req_kind.pop(rid, None)
    if kind:
        n = int(kw.get("encodedDataLength") or 0)
        counters[kind] += n
        ph = phase_counters.setdefault(phase_name[0], {"video": 0, "audio": 0, "vp-other": 0})
        ph[kind] += n

# ---- console -----------------------------------------------------------------------------
def on_console_api(**kwargs):
    args = kwargs.get("args", [])
    parts = [a.get("value", a.get("description", "")) for a in args]
    line = " ".join(str(p) for p in parts)
    if "[blankfeed]" in line:
        log("FEED", line.replace("[blankfeed] ", "")[:200])
    elif "regex mismatch" in line or "replace failure" in line:
        log("REGEXFAIL", line[:300])
    elif "[mse]" in line:
        log("MSE", line.replace("[mse] ", "")[:300])
    elif "[ymix]" in line:
        if "_progress" in line or "_po_token" in line:
            return
        log("YMIX", line.replace("[ymix] ", "")[:300])
    elif kwargs.get("type") == "error":
        log("JSERR", line[:300])

def on_log_entry(**kwargs):
    entry = kwargs.get("entry", {})
    if entry.get("level") == "error":
        log("PAGEERR", entry.get("text", "")[:200])

tab.set_listener("Runtime.consoleAPICalled", on_console_api)
tab.set_listener("Log.entryAdded", on_log_entry)
tab.set_listener("Network.requestWillBeSent", on_request)
tab.set_listener("Network.loadingFinished", on_finished)
tab.start()
tab.call_method("Log.enable")
tab.call_method("Runtime.enable")
tab.call_method("Page.enable")
tab.call_method("Network.enable")
tab.call_method("Page.addScriptToEvaluateOnNewDocument", source=MSE_PROBE)

if PLATFORM == "mobile":
    tab.call_method("Emulation.setUserAgentOverride", userAgent=MOBILE_UA, userAgentMetadata=MOBILE_UA_METADATA)
    tab.call_method("Emulation.setDeviceMetricsOverride", width=412, height=915, deviceScaleFactor=2.625, mobile=True)
    tab.call_method("Emulation.setTouchEmulationEnabled", enabled=True, maxTouchPoints=5)

def ev(expr):
    r = tab.call_method("Runtime.evaluate", expression=expr, returnByValue=True)
    return r.get("result", {}).get("value")

def action(obj):
    res = ev(f"window.ymusicAction && window.ymusicAction({json.dumps(json.dumps(obj))})")
    log(f"ACTION {obj} -> {res}")

def snap():
    s = ev("window.__mseSnap && window.__mseSnap()")
    if s:
        d = json.loads(s)
        sbs = " ".join(f"{x['id']}[{x['mime'].split(';')[0]}]={x['buf']}" for x in d.get("sbs", []))
        log(f"SNAP ct={d['ct']:.2f} rs={d['rs']} vw={d['vw']} state={d['state']} q={d['q']} pref={d['pref']} {d.get('ms','')} {sbs}")
        return d
    return None

def watch(seconds, label):
    phase_name[0] = label
    log(f"===== PHASE {label} ({seconds}s) =====")
    end = time.time() + seconds
    last_ct, stall_since = None, None
    while time.time() < end:
        d = snap()
        if d and d.get("state") == 1:
            ct = d["ct"]
            if last_ct is not None and abs(ct - last_ct) < 0.05:
                stall_since = stall_since or time.time()
            else:
                if stall_since:
                    log(f"*** stall ended after {time.time()-stall_since:.1f}s")
                stall_since = None
            last_ct = ct
        time.sleep(1)
    if stall_since:
        log(f"*** still stalled at phase end ({time.time()-stall_since:.1f}s)")
    ph = phase_counters.get(label, {})
    log(f"===== PHASE {label} bytes: video={ph.get('video',0):,} audio={ph.get('audio',0):,} other={ph.get('vp-other',0):,}")

url = f"https://www.youtube.com/embed/{VIDEO_ID}"
log(f"navigating ({PLATFORM})", url)
tab.call_method("Page.navigate", url=url)
time.sleep(9)
probe = ev("({sid: window.ymusicSessionId, action: typeof window.ymusicAction, mse: !!window.__mse})")
log("probe", probe)

action({"what": 0, "videoId": VIDEO_ID, "playWhenReady": True, "volume": 1, "startTimeSeconds": 0})
watch(PHASE1, "video-initial")

action({"what": 3, "quality": "zero"})
watch(PHASE2, "audio-only")

action({"what": 3, "quality": BACK_QUALITY})
watch(PHASE3, "back-to-video")

log("TOTAL bytes:", {k: f"{v:,}" for k, v in counters.items()})
log("per-phase:", json.dumps(phase_counters))
tab.stop()
browser.close_tab(tab)
