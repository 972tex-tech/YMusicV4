# `:playground`

Compose Multiplatform **MSE YouTube** lab: engine-js `DynamicEnginePlayer` + C leftover/ffmpeg.
One screen (`YtMsePlayerScreen`) on JVM, iOS, and Android. Video paints in a native hole
(SwingPanel / UIKitView / TextureView); Compose chrome does not recompose each frame.

## Run

- **Desktop JVM** — `./gradlew :playground:run`

BotGuard reproduce params (program, snapshot, IT, pot, SABR, entropy) go through
`cache/put` into scope `yt.bg.session` (the engine cache store). Never the logger —
newlines truncated console dumps. SPS≠1 attaches that blob on `log/capture`.
Short `bg dump meta {sessionKey, entropyLen, …}` lines stay logs. Remill from
the cache JSON: `python3 harness/extract_bg_dump.py bg-session.json`. `bg dump`
lines are DEBUG (`console.log`). Watch for:

```
bg dump meta {"why":"sps=3","sessionKey":"…","entropyLen":N,…}
```

Sentry `log/capture` attaches `bg-session.json`. Then:

```
python3 harness/extract_bg_dump.py bg-session.json
python3 harness/po_replay.py mill DIR
python3 harness/po_replay.py sps DIR
```
- **iOS** — `MainViewController()` in `iosMain` (framework `MsePlayground`); host app is local, not in git
- **Android** — `./gradlew :playground-android:installDebug` (needs an emulator/device and
  `ffmpeg-build/out/android-<abi>`)

## Layout

- `commonMain` — `YtMsePlayerScreen`, `MseNativeVideo` expect, session expect
- `jvmMain` — SwingPanel blit (`contains() = false` so clicks stay in Compose)
- `iosMain` — UIKitView + `AVSampleBufferDisplayLayer` (`isInteractive = false`)
- `androidMain` — TextureView + ANativeWindow (`setVideoSurface`)
- `:playground-android` — thin AGP application shell (AGP 9 cannot combine `application` with KMP)
