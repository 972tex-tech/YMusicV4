"""Summarize the matrix result CSVs into a markdown table.

Each log line: t_ms,src,state,detail (detail may itself contain commas).
"""
import glob
import os
import re
import sys

results_dir = sys.argv[1] if len(sys.argv) > 1 else "results"


def parse(path):
    rows = []
    with open(path) as f:
        for line in f:
            parts = line.rstrip("\n").split(",", 3)
            if len(parts) < 4:
                continue
            try:
                t = int(parts[0])
            except ValueError:
                continue
            rows.append((t, parts[1], parts[2], parts[3]))
    return rows


def media_time(detail):
    m = re.search(r"(?:^t=|nativeT=)([0-9.]+)", detail)
    return float(m.group(1)) if m else None


def analyze(path):
    rows = parse(path)
    name = os.path.basename(path)[:-4]
    t_bg = next((t for t, s, _, d in rows if s == "lifecycle" and d.startswith("didEnterBackground")), None)
    t_fg = next(
        (t for t, s, _, d in rows if s == "lifecycle" and d.startswith("willEnterForeground") and (t_bg is None or t > t_bg)),
        None,
    )
    is_native = "-native" in name

    def ticks(pred):
        out = []
        for t, s, st, d in rows:
            if is_native and s == "native" and "nativeT=" in d and pred(t, st):
                out.append((t, media_time(d), "playing=true" in d))
            elif not is_native and s == "js-tick" and pred(t, st):
                out.append((t, media_time(d), "paused=false" in d))
        return out

    fg_ticks = ticks(lambda t, st: t_bg is None or t < t_bg)
    bg_ticks = ticks(lambda t, st: t_bg is not None and t > t_bg and (t_fg is None or t < t_fg))

    fg_play = any(mt and mt > 2 and playing for _, mt, playing in fg_ticks)

    # a forced pause right after backgrounding (not the natural track-1 end at t~20)
    forced_pause = None
    for t, s, st, d in rows:
        if t_bg and s == "js-ev" and d.startswith("pause") and t > t_bg:
            mt = media_time(d.split(" ", 1)[1] if " " in d else "")
            m = re.search(r"t=([0-9.]+)", d)
            mt = float(m.group(1)) if m else None
            if mt is None or abs(mt - 20.0) > 0.5:
                forced_pause = (t, d)
                break

    bg_advancing = False
    if len(bg_ticks) >= 2:
        # media clock advance across the bg window (track change resets it, so check any advance)
        for (t1, m1, p1), (t2, m2, p2) in zip(bg_ticks, bg_ticks[1:]):
            if m1 is not None and m2 is not None and (m2 > m1 or m2 < m1 - 5) and p2:
                bg_advancing = True
                break

    track2_bg = any(
        s in ("js-ev", "native") and st == "background" and ("track2 play ok" in d or "track2 play" in d or d == "track finished ok=true")
        for _, s, st, d in rows
    )

    # app suspension: max wall-clock gap between ANY two consecutive log lines in the bg window
    bg_rows = [t for t, *_ in rows if t_bg and t > t_bg and (t_fg is None or t < t_fg)]
    max_gap = max((b - a for a, b in zip(bg_rows, bg_rows[1:])), default=0) if len(bg_rows) >= 2 else None
    last_bg_t = bg_rows[-1] if bg_rows else None
    bg_window_end = t_fg if t_fg else (rows[-1][0] if rows else None)
    died_early = last_bg_t is not None and bg_window_end and (bg_window_end - last_bg_t) > 5000

    resumed = False
    if t_fg:
        post = ticks(lambda t, st: t > t_fg)
        if len(post) >= 2 and post[-1][1] is not None and post[0][1] is not None:
            resumed = post[-1][1] > post[0][1]

    interruptions = [(t, d) for t, s, _, d in rows if s == "session" and "interruption" in d]
    cfg = [d for _, s, _, d in rows if s == "cfg"]

    return {
        "name": name,
        "fg_play": fg_play,
        "forced_pause": forced_pause,
        "bg_advancing": bg_advancing,
        "track2_bg": track2_bg,
        "max_gap_ms": max_gap,
        "died_early": died_early,
        "resumed": resumed,
        "t_fg": t_fg,
        "interruptions": interruptions,
        "cfg": cfg,
    }


def yn(b):
    return "✅" if b else "❌"


print("| run | fg plays | bg keeps playing | bg track change | forced pause on bg | app suspended in bg | resumes on fg |")
print("|---|---|---|---|---|---|---|")
notes = []
for path in sorted(glob.glob(os.path.join(results_dir, "*.csv"))):
    r = analyze(path)
    suspended = r["died_early"] or (r["max_gap_ms"] or 0) > 5000
    print(
        f"| {r['name']} | {yn(r['fg_play'])} | {yn(r['bg_advancing'])} | {yn(r['track2_bg'])} "
        f"| {'⚠️ ' + r['forced_pause'][1] if r['forced_pause'] else '—'} "
        f"| {'YES (gap ' + str(r['max_gap_ms']) + 'ms)' if suspended else 'no'} "
        f"| {yn(r['resumed']) if r['t_fg'] else 'n/a'} |"
    )
    if r["interruptions"]:
        notes.append(f"- {r['name']}: session interruptions: {r['interruptions']}")
    for c in r["cfg"]:
        if "UNAVAILABLE" in c or "error" in c:
            notes.append(f"- {r['name']}: {c}")
print()
for n in notes:
    print(n)
