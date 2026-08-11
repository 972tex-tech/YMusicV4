"""End-to-end sweep of every ymusicAction command (all 16 `what` codes) against the injected bundle
in a throwaway Chrome (proxied through mitmproxy, muted). For each command it records the synchronous
return, the `[ymix]` events it produced, and — where one exists — a player-state readback, then
prints PASS/FAIL. Also checks the error contract (bad params / unknown action -> 'error').

Prereqs: `mitmdump -s test/inject.py` running, dist built, `pip install pychrome`, and Chrome up with
    --remote-debugging-port=9222 --mute-audio --proxy-server=http://127.0.0.1:8080 --ignore-certificate-errors
See run_commands.sh, which launches Chrome that way. Tests are MUTED (player volume + --mute-audio).

    commands.py [videoId] [mobile|desktop]
"""
import json
import sys
import threading
import time

import pychrome

VIDEO = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
SECOND = "9bZkp7q19f0"  # a different id, for preload / addWantedPlayerRes
PLATFORM = sys.argv[2] if len(sys.argv) > 2 else "mobile"

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
)
MOBILE_META = {
    "platform": "Android", "platformVersion": "14", "architecture": "", "model": "Pixel 7 Pro",
    "mobile": True,
    "brands": [{"brand": "Chromium", "version": "150"}, {"brand": "Google Chrome", "version": "150"}],
}

events = []  # (t, channel, payload_str)
lock = threading.Lock()
t0 = time.time()


def now():
    return time.time() - t0


def on_log(**kwargs):
    args = kwargs.get("args", [])
    parts = [a.get("value", a.get("description", "")) for a in args]
    line = " ".join(str(p) for p in parts)
    if line.startswith("[ymix] "):
        rest = line[len("[ymix] "):]
        channel, _, payload = rest.partition(" ")
        with lock:
            events.append((now(), channel, payload))


browser = pychrome.Browser(url="http://127.0.0.1:9222")
tab = browser.new_tab()
tab.set_listener("Runtime.consoleAPICalled", on_log)
tab.start()
tab.call_method("Runtime.enable")
tab.call_method("Page.enable")

if PLATFORM == "mobile":
    tab.call_method("Emulation.setUserAgentOverride", userAgent=MOBILE_UA, userAgentMetadata=MOBILE_META)
    tab.call_method("Emulation.setDeviceMetricsOverride", width=412, height=915, deviceScaleFactor=2.625, mobile=True)
    tab.call_method("Emulation.setTouchEmulationEnabled", enabled=True, maxTouchPoints=5)


def ev(expr):
    r = tab.call_method("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
    return r.get("result", {}).get("value")


def player(method):
    return ev(f"(function(){{try{{var p=document.querySelector('#movie_player');return p&&p.{method};}}catch(e){{return 'ERR:'+e;}}}})()")


def act(params):
    """Call ymusicAction; return the synchronous 'ok'/'error'."""
    return ev(f"window.ymusicAction(JSON.stringify({json.dumps(params)}))")


def since(t, channel=None):
    with lock:
        return [(c, p) for (te, c, p) in events if te >= t and (channel is None or c.endswith(channel))]


def wait_state_changed(t, deadline_s, predicate):
    """Poll up to deadline_s for a state_changed event since t whose parsed payload satisfies predicate."""
    end = time.time() + deadline_s
    while time.time() < end:
        for c, p in since(t, "_state_changed"):
            try:
                if predicate(json.loads(p)):
                    return True
            except (ValueError, TypeError):
                pass
        time.sleep(0.4)
    return False


results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name:26} {detail}", flush=True)


print(f"navigating ({PLATFORM}) https://www.youtube.com/embed/{VIDEO}", flush=True)
tab.call_method("Page.navigate", url=f"https://www.youtube.com/embed/{VIDEO}")
time.sleep(8)
boot = ev("({action: typeof window.ymusicAction, mobileUA: /Mobile/.test(navigator.userAgent)})")
print("boot", boot, flush=True)

# 0 Load — muted (volume:0). The embed auto-loads the same id, so a same-video Load may not re-emit
# a state transition; the deterministic "loaded" signal is the player reporting a duration.
t = now(); r = act({"what": 0, "videoId": VIDEO, "playWhenReady": True, "volume": 0, "startTimeSeconds": 0})
end = time.time() + 12
dur = 0
while time.time() < end and not (isinstance(dur, (int, float)) and dur > 0):
    dur = player("getDuration()") or 0
    time.sleep(0.4)
check("0 Load", r == "ok" and isinstance(dur, (int, float)) and dur > 0, f"ret={r} duration={dur}")
time.sleep(1)

# 10 SetVolume — readback getVolume() (still muted at output via --mute-audio).
t = now(); r = act({"what": 10, "volume": 0.5}); time.sleep(1)
vol = player("getVolume()")
check("10 SetVolume", r == "ok" and vol == 50, f"ret={r} getVolume={vol}")
act({"what": 10, "volume": 0})  # back to silent

# 11 SetSpeed — readback getPlaybackRate().
t = now(); r = act({"what": 11, "speed": 1.5}); time.sleep(1)
rate = player("getPlaybackRate()")
check("11 SetSpeed", r == "ok" and rate == 1.5, f"ret={r} rate={rate}")
act({"what": 11, "speed": 1})

# 4 Seek — readback getCurrentTime() near target.
t = now(); r = act({"what": 4, "positionSeconds": 100}); time.sleep(2)
ct = player("getCurrentTime()")
check("4 Seek", r == "ok" and isinstance(ct, (int, float)) and ct >= 95, f"ret={r} t={ct}")

# 2 SetPlayWhenReady(false) — the intent propagates as a state_changed with playWhenReady:false
# (asserting the bridge event, not live player state, which may be mid-buffer).
t = now(); r = act({"what": 2, "playWhenReady": False})
off = wait_state_changed(t, 4, lambda d: d.get("playWhenReady") is False)
check("2 SetPlayWhenReady off", r == "ok" and off, f"ret={r} sawPWR=false:{off}")

# 2 SetPlayWhenReady(true) — state_changed with playWhenReady:true.
t = now(); r = act({"what": 2, "playWhenReady": True})
on = wait_state_changed(t, 4, lambda d: d.get("playWhenReady") is True)
check("2 SetPlayWhenReady on", r == "ok" and on, f"ret={r} sawPWR=true:{on}")

# 3 SetVideoQuality — deterministic contract is the ok dispatch; the quality_changed event is
# player-driven (fires only if the ACTUAL quality changes, which on a small mobile viewport it
# often doesn't), so it's reported as informational, not asserted. An invalid quality is also a
# silent no-op ('ok'), so we assert a valid one dispatches and an invalid one still doesn't error.
t = now(); r = act({"what": 3, "quality": "hd720"}); time.sleep(2)
r_bad = act({"what": 3, "quality": "not-a-quality"})
q = [p for (c, p) in since(t, "_quality_changed")]
check("3 SetVideoQuality", r == "ok" and r_bad == "ok", f"ret={r} invalid={r_bad} quality_changed={len(q)}")

# 7 SetCaptionLanguage — return ok (captions event best-effort).
t = now(); r = act({"what": 7, "languageCode": "en"}); time.sleep(1.5)
check("7 SetCaptionLanguage", r == "ok", f"ret={r}")

# 9 SetCaptionVisible — DOM-only; return ok.
r = act({"what": 9, "visible": False})
check("9 SetCaptionVisible", r == "ok", f"ret={r}")

# 6 SetLoop — return ok.
r = act({"what": 6, "enable": True})
check("6 SetLoop", r == "ok", f"ret={r}")

# 15 SetScaleMode — video element gets the object-fit class.
r = act({"what": 15, "scaleMode": 1}); time.sleep(0.5)
cls = ev("(document.querySelector('video')||{}).className||''")
check("15 SetScaleMode", r == "ok" and "object-fit" in (cls or ""), f"ret={r} class~={('object-fit' in (cls or ''))}")

# 8 SetAudioTrack — best-effort (no multi-track on most VODs); return ok.
r = act({"what": 8, "trackId": "0"})
check("8 SetAudioTrack", r == "ok", f"ret={r}")

# 13 AddWantedPlayerRes + 1 Preload — expect a player_res for SECOND.
t = now(); r13 = act({"what": 13, "videoId": SECOND}); r1 = act({"what": 1, "videoId": SECOND}); time.sleep(4)
pres = [p for (c, p) in since(t, "_player_res")]
check("13 AddWantedPlayerRes", r13 == "ok", f"ret={r13}")
check("1 Preload", r1 == "ok" and any(SECOND in p for p in pres), f"ret={r1} player_res={len(pres)}")

# 14 SeekToLiveHead — no-op on VOD; return ok.
r = act({"what": 14})
check("14 SeekToLiveHead", r == "ok", f"ret={r}")

# 16 GetPoToken — po_token_v2 {success:true, token}.
t = now(); r = act({"what": 16, "videoId": VIDEO, "timeout": 10000}); time.sleep(6)
tok = [p for (c, p) in since(t, "_po_token_v2")]
ok16 = r == "ok" and any('"success":true' in p.replace(" ", "") for p in tok)
check("16 GetPoToken", ok16, f"ret={r} po_token_v2={len(tok)}")

# 12 Bulk — runs volume=0 + speed=1 + pause in one call; verify each sub-action's effect.
t = now(); r = act({"what": 12, "actions": [
    {"what": 10, "volume": 0}, {"what": 11, "speed": 1}, {"what": 2, "playWhenReady": False}]})
paused = wait_state_changed(t, 4, lambda d: d.get("playWhenReady") is False)
vol = player("getVolume()"); rate = player("getPlaybackRate()")
check("12 Bulk", r == "ok" and vol == 0 and rate == 1 and paused, f"ret={r} vol={vol} rate={rate} pause={paused}")

# 5 Stop — last, since it unloads.
t = now(); r = act({"what": 5}); time.sleep(1.5)
st = player("getPlayerState()")
check("5 Stop", r == "ok", f"ret={r} state={st}")

# --- error contract ---
check("neg: volume>1 -> error", act({"what": 10, "volume": 5}) == "error")
check("neg: seek NaN -> error", act({"what": 4, "positionSeconds": "x"}) == "error")
t = now(); rr = act({"what": 999})
rerr = [p for (c, p) in since(t) if "report_error" in c]
check("neg: unknown -> error", rr == "error", f"ret={rr}")

passed = sum(1 for _, ok, _ in results if ok)
print(f"\n=== {passed}/{len(results)} passed ===", flush=True)
tab.stop()
browser.close_tab(tab)
sys.exit(0 if passed == len(results) else 1)
