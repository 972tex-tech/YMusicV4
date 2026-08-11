# iOS WKWebView background-audio spike

A standalone, scriptable iOS app that measures whether `<audio>` playing inside a
**WKWebView keeps playing when the app is backgrounded**, across the known workarounds.
Built for the YMusic V4 player question in
[`docs/player/youtube-playback.md`](../../docs/player/youtube-playback.md#the-ios-background-audio-problem-the-central-risk):
YouTube playback is WebView-only, so background audio through the WebView is
make-or-break on iOS.

No Xcode project — one Swift file compiled with `swiftc` into a simulator `.app`,
installed and driven entirely with `simctl`.

## Layout

- `src/main.swift` — the whole app. A `-mode <name>` launch argument selects the
  workaround set; everything observable (JS media events, 1 s media-clock ticks, app
  lifecycle, audio-session interruptions) is appended to `Documents/spike-log.csv`.
- `web/index.html` — the page: an `<audio>` element, `start()`/`kick()` hooks, a
  track-1 → track-2 change on `ended` (exercises "next track while backgrounded"),
  1 s ticks posted via `webkit.messageHandlers`.
- `tools/gen_audio.py` — generates the WAV fixtures (20 s 440 Hz track1, 120 s 660 Hz
  track2, silent loop).
- `build.sh` — builds two bundle variants: `BgAudioSpike` (**with**
  `UIBackgroundModes: audio`) and `BgAudioSpikeNoBG` (without).
- `run_matrix.sh` — runs every mode on the booted simulator: launch → autoplay → 8 s
  foreground → background (opens Settings) → 35 s background window spanning the track
  change → foreground via `bgspike://` URL scheme → collect the CSV into `results/`.
- `tools/summarize.py results` — renders the results table below.

## Modes

| mode | what it adds |
|---|---|
| `plain` | `UIBackgroundModes: audio` in the plist only; `AVAudioSession` never touched |
| `session` | + `AVAudioSession` category `.playback`, `setActive(true)` |
| `nowplaying` | session + `MPNowPlayingInfoCenter` info + `MPRemoteCommandCenter` targets |
| `silence` | session + a native silent `AVAudioPlayer` loop (the classic keep-alive hack) |
| `kick` | session + on `didEnterBackground`: re-`setActive` + `evaluateJavaScript("kick()")` (`audio.play()`) |
| `private` | session + WebKit private API `_alwaysRunsAtForegroundPriority = true` |
| `all` | session + nowplaying + silence + kick + private |
| `native` | control group — the same tracks through a native `AVAudioPlayer`, no WebView |

The `NoBG` variant re-runs `session` and `native` **without** the background-audio
entitlement.

## Running it

```bash
./build.sh          # needs Xcode + a booted iOS simulator
./run_matrix.sh     # ~9 min for the 10-run matrix
python3 tools/summarize.py results
```

## Results — iPhone 15 Pro simulator, iOS 17.4, 2026-07-11

Backgrounded by app-switch (opening Settings); raw logs in `results/`:

| run | fg plays | bg keeps playing | bg track change | forced pause on bg | app suspended in bg | resumes on fg |
|---|---|---|---|---|---|---|
| BgAudioSpike-plain | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-session | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-nowplaying | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-silence | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-kick | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-private | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-all | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpike-native | ✅ | ✅ | ✅ | — | no | ✅ |
| BgAudioSpikeNoBG-session | ✅ | ✅ | ✅ | — | no | n/a |
| **BgAudioSpikeNoBG-native** | ✅ | ✅ | ✅ | — | no | ✅ |

Backgrounded by **locking the screen** (Device ▸ Lock); raw logs in `results-lock/`:

| run | fg plays | bg keeps playing | bg track change | forced pause on bg |
|---|---|---|---|---|
| BgAudioSpike-plain | ✅ | ✅ | ✅ | — |
| BgAudioSpike-session | ✅ | ✅ | ✅ | — |
| BgAudioSpike-native | ✅ | ✅ | ✅ | — |
| BgAudioSpikeNoBG-session | ✅ | ✅ | ✅ | — |
| BgAudioSpikeNoBG-native | ✅ | ✅ | ✅ | — |

Every lock run verifiably backgrounded (`didEnterBackground` + `visibility=hidden` in
the logs).

## Conclusion

1. **The simulator cannot falsify background audio.** The control run
   `BgAudioSpikeNoBG-native` — a plain native `AVAudioPlayer` with **no**
   `UIBackgroundModes` entitlement — kept playing through both app-switch and screen
   lock. On any real device that is silenced at suspension. The simulator does not
   enforce the background-audio policy, so every ✅ above is necessary-but-not-
   sufficient evidence; **only a device run can gate the iOS timeline**.
2. What the simulator **did** establish (WebKit-level mechanisms, same code paths as
   device):
   - **WebKit does not hard-pause the media element on backgrounding or lock.** No
     forced `pause` fired in any of the 15 runs (with `allowsInlineMediaPlayback` +
     `mediaTypesRequiringUserActionForPlayback = []`). The element's clock advanced
     the entire background window.
   - **The in-background track change works**: `ended` → set `src` → `play()`
     succeeded while backgrounded/locked in every mode — page JS keeps running while
     its audio plays, and the follow-up `play()` needs no user gesture. This is the
     "next track while pocketed" case YMusic needs.
   - **WKWebView auto-publishes Now Playing**: the lock screen showed a media widget
     titled from the page `<title>` in `plain` mode with zero `MPNowPlayingInfoCenter`
     code. The two-sources reconciliation problem in
     [`system-integration.md`](../../docs/player/system-integration.md) is real and
     immediate.
   - **JS timers throttle 1 s → 2 s in background** — the ymix bridge must not depend
     on fine-grained JS timers when backgrounded.
   - The private API `_alwaysRunsAtForegroundPriority` still exists and applies on
     iOS 17.4 (logged `applied`); kept in the matrix only as a last-resort lever.
3. **Corroboration from the earlier client prototype** (KMP Compose, WKWebView
   playing real YouTube): the user observed working background playback there, and
   that project has **no `UIBackgroundModes`, no `AVAudioSession` code, no
   now-playing code at all** — consistent with WKWebView's media stack handling the
   audio session itself, out of process.
4. Recommended app-side posture regardless of device findings (cheap, standard, and
   what every shipping WebView-audio app does): `UIBackgroundModes: audio` +
   `AVAudioSession(.playback)` active before playback. The exotic hacks
   (silent keep-alive loop, background `play()` kick, private API) showed **no
   additional effect** in any simulator scenario — hold them in reserve for the
   device spike rather than building them into the architecture.

## BgAudioLab — the manual on-device app

`BgAudioLab/BgAudioLab.xcodeproj` is a real Xcode project for **hand-testing on a
device** (iPhone or iPad; the simulator matrix above can't enforce background policy).
Open it in Xcode, let automatic signing provision it (team is preset), and run.

How to use:

1. Pick an **engine**: `WebView` (local WAVs through WKWebView — the controlled case),
   `YouTube` (loads a real `m.youtube.com` watch page; **tap play in the page**), or
   `Native` (`AVAudioPlayer` control group).
2. Toggle **workarounds** (audio session on by default), hit **Start test**.
3. Background or lock the device by hand, wait ~20 s, come back. Each background
   episode gets a row: **✅ kept playing** / **❌ stopped** (with cause: paused by
   system, page frozen, no progress), computed from the media clock.
4. **Arm delayed start (8 s)** then background immediately — tests whether playback
   can *begin* while backgrounded (the known-broken case; with *silent keep-alive* on,
   the app stays alive to attempt it).
5. The WebView track change happens automatically at 20 s (track1 → track2) — leave it
   backgrounded across that boundary to test "next track while pocketed".
6. **Share log CSV** exports the full log (also reachable via the Files app).

Scripted use (simulator or `devicectl`): launch args `-engine webview|youtube|native
-autostart`.

The interesting device matrix, smallest first: WebView+session (baseline expectation:
works) → leave locked >5 min (does it survive long suspensions?) → delayed start
(expected broken; does silent keep-alive fix it?) → YouTube+session (the real thing) →
each failure retried with the next workaround toggled on.

## Caveat

This is the **simulator**, not a device. App suspension and the WebKit media policies
do run in the simulator, but the OS-level audio-session arbitration (other apps,
Control Center, real interruptions, low-power) does not fully. Treat these results as
mechanism evidence — the device spike remains the gating step before committing the
iOS timeline (as `docs/player/youtube-playback.md` already requires).
