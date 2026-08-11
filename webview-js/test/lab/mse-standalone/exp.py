"""Run the blank-splice MSE experiments in the lab Chrome via CDP and assert continuity."""
import json
import time

import pychrome

browser = pychrome.Browser(url="http://127.0.0.1:9223")
tab = browser.new_tab()
t0 = time.time()

def log(*a):
    print(f"[{time.time() - t0:6.1f}s]", *a, flush=True)

def on_console_api(**kwargs):
    args = kwargs.get("args", [])
    line = " ".join(str(a.get("value", a.get("description", ""))) for a in args)
    if "[lab]" in line:
        log("LAB", line.replace("[lab] ", "")[:240])

tab.set_listener("Runtime.consoleAPICalled", on_console_api)
tab.start()
tab.call_method("Runtime.enable")
tab.call_method("Page.enable")
tab.call_method("Page.navigate", url="http://127.0.0.1:8791/index.html")
time.sleep(2)

def ev(expr, awaitPromise=False):
    r = tab.call_method("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=awaitPromise, timeout=20000)
    if "exceptionDetails" in r:
        log("EXC", json.dumps(r["exceptionDetails"])[:400])
    return r.get("result", {}).get("value")

def snap():
    s = ev("window.snap && window.snap()")
    d = json.loads(s) if s else {}
    log(f"SNAP ct={d.get('ct',0):.2f} rs={d.get('rs')} vw={d.get('vw')} waits={d.get('waits')} errs={d.get('errs')} a={d.get('a','')[:24]} v={d.get('vb','')[:44]}")
    return d

def watch(sec, label):
    log(f"--- {label} ({sec}s)")
    last, stalls = None, 0
    end = time.time() + sec
    while time.time() < end:
        d = snap()
        ct = d.get("ct", 0)
        if last is not None and abs(ct - last) < 0.05 and not d.get("paused"):
            stalls += 1
            log(f"*** no progress ({stalls})")
        last = ct
        time.sleep(1)
    return stalls

log("setup ->", ev("window.setup()", awaitPromise=True))
s1 = watch(6, "E0 real playback")
log("spliceBlank ->", ev("window.spliceBlank()", awaitPromise=True))
# play through the splice point (~30s buffered end); currentTime is ~6-8s here, so seek near splice
ev("document.getElementById('v').currentTime = 26")
s2 = watch(12, "E1 crossing splice into blank")
log("resumeReal ->", ev("window.resumeReal()", awaitPromise=True))
s3 = watch(8, "E2 back to real video")
ev("document.getElementById('v').currentTime = 70")
log("seeked to 70 (blank region only)")
log("re-splice for seek test ->", ev("window.spliceBlank()", awaitPromise=True))
s4 = watch(8, "E3 seek into blank region")
d = snap()
log(f"RESULT stalls: E0={s1} E1={s2} E2={s3} E3={s4} errs={d.get('errs')}")
tab.stop()
browser.close_tab(tab)
