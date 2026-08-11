"""Repeated video<->zero toggle robustness test on the mobile player, asserting seamlessness:
count MediaSource teardowns (video.emptied after first) and playback stalls per phase."""
import json, sys, time, threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import pychrome

VIDEO_ID = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")
MOBILE_META = {"platform": "Android", "platformVersion": "14", "architecture": "", "model": "Pixel 7 Pro",
               "mobile": True, "brands": [{"brand": "Chromium", "version": "150"}]}
MSE_PROBE = (Path(__file__).parent / "mse_probe.js").read_text()

browser = pychrome.Browser(url="http://127.0.0.1:9223")
tab = browser.new_tab(); t0 = time.time(); lock = threading.Lock()
teardowns = [0]; feeds = [0]; vbytes = [0]

def log(*a):
    with lock: print(f"[{time.time()-t0:6.1f}s]", *a, flush=True)

def on_console(**k):
    line = " ".join(str(a.get("value", a.get("description", ""))) for a in k.get("args", []))
    if '"ev":"video.emptied"' in line: teardowns[0] += 1
    if "[blankfeed] blank" in line: feeds[0] += 1
    if "report_error" in line or k.get("type") == "error": log("ERR", line[:200])

def on_req(**k):
    url = k.get("request", {}).get("url", "")
    if "videoplayback" in url or "ymusicForward" in url:
        q = parse_qs(urlparse(url).query)
        if (q.get("mime") or [""])[0].startswith("video/"):
            vbytes[0] += 1  # count video segment requests

tab.set_listener("Runtime.consoleAPICalled", on_console)
tab.set_listener("Network.requestWillBeSent", on_req)
tab.start()
for m in ("Runtime.enable", "Page.enable", "Network.enable"): tab.call_method(m)
tab.call_method("Page.addScriptToEvaluateOnNewDocument", source=MSE_PROBE)
tab.call_method("Emulation.setUserAgentOverride", userAgent=MOBILE_UA, userAgentMetadata=MOBILE_META)
tab.call_method("Emulation.setDeviceMetricsOverride", width=412, height=915, deviceScaleFactor=2.625, mobile=True)

def ev(e, aw=False):
    return tab.call_method("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=aw).get("result", {}).get("value")
def act(o): log("ACT", o, "->", ev(f"window.ymusicAction({json.dumps(json.dumps(o))})"))
def snap():
    s = ev("window.__mseSnap && window.__mseSnap()")
    return json.loads(s) if s else {}

def watch(sec, label):
    log(f"-- {label}"); last = None; stalls = 0; end = time.time() + sec
    while time.time() < end:
        d = snap(); ct = d.get("ct", 0)
        if last is not None and abs(ct - last) < 0.05 and d.get("state") == 1: stalls += 1
        last = ct; time.sleep(1)
    d = snap()
    log(f"   end ct={d.get('ct',0):.1f} state={d.get('state')} q={d.get('q')} stalls={stalls} sbs={[(x['id'],x['buf']) for x in d.get('sbs',[])]}")
    return stalls

tab.call_method("Page.navigate", url=f"https://www.youtube.com/embed/{VIDEO_ID}")
time.sleep(9)
act({"what": 0, "videoId": VIDEO_ID, "playWhenReady": True, "volume": 1})
s = [watch(10, "video")]
for i in range(3):
    act({"what": 3, "quality": "zero"}); s.append(watch(8, f"zero#{i+1}"))
    act({"what": 3, "quality": "hd720"}); s.append(watch(8, f"video#{i+1}"))
log("RESULT teardowns(after 1st)=", teardowns[0]-1, "blankFeeds=", feeds[0], "videoSegReqs=", vbytes[0], "stalls/phase=", s)
tab.stop(); browser.close_tab(tab)
