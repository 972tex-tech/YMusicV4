"""Instrument SABR/UMP at the network layer via CDP to see the WHOLE picture of a /videoplayback
exchange: decode the request's ClientAbrState (position, audio-only flags 16/40/46, selected
formats) and walk the UMP response parts, surfacing the control parts that matter —
SABR_ERROR(44), STREAM_PROTECTION_STATUS(58), RELOAD_PLAYER_RESPONSE(46), SABR_REDIRECT(43),
SABR_CONTEXT_UPDATE(57), FORMAT_INIT(42), and any next-request backoff/policy — plus whether MEDIA
parts (20/21/22) actually flowed. Answers: is a 'block' really an IP ban, or a backoff/ad-context
/attestation gate telling the client to retry?

    sabr_probe.py <videoId> [mobile|desktop] [seconds]
"""
import base64, sys, time, threading
import pychrome

VID = sys.argv[1] if len(sys.argv) > 1 else "dQw4w9WgXcQ"
PLATFORM = sys.argv[2] if len(sys.argv) > 2 else "mobile"
SECS = int(sys.argv[3]) if len(sys.argv) > 3 else 30
MOBILE_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 7 Pro) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36")

# ---- minimal protobuf + UMP varint ----
def rv(b, i):
    "read base-128 varint -> (value, next_index)"
    shift = 0; out = 0
    while i < len(b):
        c = b[i]; i += 1
        out |= (c & 0x7f) << shift
        if not (c & 0x80): break
        shift += 7
    return out, i

def pb_fields(b):
    "yield (field_num, wire_type, value_bytes_or_int)"
    i = 0; out = []
    while i < len(b):
        tag, i = rv(b, i)
        fn, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = rv(b, i); out.append((fn, wt, v))
        elif wt == 2:
            ln, i = rv(b, i); out.append((fn, wt, b[i:i+ln])); i += ln
        elif wt == 5:
            out.append((fn, wt, b[i:i+4])); i += 4
        elif wt == 1:
            out.append((fn, wt, b[i:i+8])); i += 8
        else:
            break
    return out

def ump_parts(b):
    "UMP: [partId varint][size varint][data] -> yield (partId, data)"
    i = 0; out = []
    while i < len(b):
        try:
            pid, i = rv(b, i)
            sz, i = rv(b, i)
        except Exception:
            break
        out.append((pid, b[i:i+sz])); i += sz
        if sz == 0 and pid == 0: break
    return out

UMP_NAMES = {20: "MEDIA_HEADER", 21: "MEDIA", 22: "MEDIA_END", 42: "FORMAT_INIT",
             43: "SABR_REDIRECT", 44: "SABR_ERROR", 45: "SABR_SEEK",
             46: "RELOAD_PLAYER_RESPONSE", 57: "SABR_CTX_UPDATE",
             58: "STREAM_PROTECTION_STATUS", 59: "SABR_CTX_SENDING_POLICY", 67: "SNACKBAR"}

browser = pychrome.Browser(url="http://127.0.0.1:9223")
tab = browser.new_tab(); t0 = time.time(); lock = threading.Lock()
req_bodies = {}

def log(*a):
    with lock: print(f"[{time.time()-t0:6.1f}s]", *a, flush=True)

def decode_client_abr(body):
    "field 1 = ClientAbrState; report position + audio-only flags + selected format itags"
    root = pb_fields(body)
    info = {}
    cas = next((v for (fn, wt, v) in root if fn == 1 and wt == 2), None)
    if cas:
        flags = {}
        for (fn, wt, v) in pb_fields(cas):
            if fn in (14, 15, 16, 40, 46) and wt == 0:
                flags[fn] = v
        info["clientAbrState_flags(16/40/46=audioOnly)"] = flags
    itags = [pb_fields(v)[0][2] for (fn, wt, v) in root if fn == 17 and wt == 2 and pb_fields(v)]
    if itags: info["selectedFormatItags"] = itags
    return info

def on_req(**kw):
    r = kw.get("request", {}); url = r.get("url", "")
    if "/videoplayback" not in url and "ymusicForward" not in url: return
    pd = r.get("postData")
    if pd:
        try:
            body = base64.b64decode(pd) if r.get("postDataEntries") else pd.encode("latin1")
            info = decode_client_abr(body)
            log("SABR>", ("sabr" if "sabr=1" in url else "range"), info or "(no abr)")
        except Exception as e:
            log("SABR> decode err", e)

def on_resp(**kw):
    resp = kw.get("response", {}); url = resp.get("url", "")
    if "/videoplayback" not in url and "ymusicForward" not in url: return
    ct = resp.get("headers", {}).get("content-type") or resp.get("headers", {}).get("Content-Type", "")
    rid = kw.get("requestId")
    st = resp.get("status")
    # fetch body after it finishes
    def grab():
        time.sleep(0.4)
        try:
            r = tab.call_method("Network.getResponseBody", requestId=rid)
            data = base64.b64decode(r["body"]) if r.get("base64Encoded") else r["body"].encode("latin1")
        except Exception:
            return
        if "ump" in ct or "octet-stream" in ct or data[:1] in (b"\x14", b"\x1a", b"\x00", b"\x2a", b"\x2c"):
            parts = ump_parts(data)
            summary = {}
            for pid, d in parts:
                nm = UMP_NAMES.get(pid, f"part{pid}")
                summary[nm] = summary.get(nm, 0) + 1
                if pid == 44:  # SABR_ERROR {type:1(str), code:2(int)}
                    f = pb_fields(d); log("  !! SABR_ERROR", f[:4])
                if pid == 58:  # STREAM_PROTECTION_STATUS {status:1, max_retries:2}
                    f = {fn: v for (fn, wt, v) in pb_fields(d)}
                    log(f"  !! STREAM_PROTECTION status={f.get(1)} max_retries={f.get(2)}")
                if pid == 46:  # RELOAD_PLAYER_RESPONSE
                    log("  !! RELOAD_PLAYER_RESPONSE (session invalid -> refetch /player)")
                if pid == 43:
                    log("  -> SABR_REDIRECT")
                if pid == 67:
                    log("  -> SNACKBAR", pb_fields(d)[:2])
            has_media = any(p in summary for p in ("MEDIA", "MEDIA_HEADER"))
            log(f"SABR< status={st} ct={ct[:30]} parts={summary} media={'YES' if has_media else 'NO'}")
        else:
            log(f"VP< status={st} ct={ct[:30]} bytes={len(data)} (plain range)")
    threading.Thread(target=grab, daemon=True).start()

tab.set_listener("Network.requestWillBeSent", on_req)
tab.set_listener("Network.responseReceived", on_resp)
tab.start()
for m in ("Runtime.enable", "Network.enable", "Page.enable"): tab.call_method(m)
if PLATFORM == "mobile":
    tab.call_method("Emulation.setUserAgentOverride", userAgent=MOBILE_UA)
    tab.call_method("Emulation.setDeviceMetricsOverride", width=412, height=915, deviceScaleFactor=2.6, mobile=True)
tab.call_method("Page.navigate", url=f"https://www.youtube.com/embed/{VID}")
time.sleep(9)
def ev(e): return tab.call_method("Runtime.evaluate", expression=e, returnByValue=True).get("result", {}).get("value")
log("load ->", ev(f'window.ymusicAction(JSON.stringify({{what:0,videoId:"{VID}",playWhenReady:true,volume:1}}))'))
end = time.time() + SECS
while time.time() < end:
    time.sleep(2)
    log("player", ev("(function(){var p=document.getElementById('movie_player');var v=document.querySelector('video');return JSON.stringify({state:p.getPlayerState(),ct:v?+v.currentTime.toFixed(1):-1})})()"))
tab.stop(); browser.close_tab(tab)
