# `:playground`

Compose Multiplatform **MSE YouTube** lab: engine-js `DynamicEnginePlayer` + C leftover/ffmpeg.
One screen (`YtMsePlayerScreen`) on JVM, iOS, and Android. Video paints in a native hole
(SwingPanel / UIKitView / TextureView); Compose chrome does not recompose each frame.

## Run

- **Desktop JVM** — `./gradlew :playground:run`

BotGuard capture (no cookies) lands in `build/bg-dump/<yyyyMMdd-HHmmss-SSS>/`
(UTC stamp = session key; `build/bg-dump/latest` points at the newest). Scalars
live in `meta.json`; only large payloads are separate files. `BG_DUMP` defaults
on unless `-Pproduction=true`. Watch for:

```
bg capture miller dir=… program=N snap=N it=N
bg capture sabr dir=… body=N
bg capture sps N
```

Then in js-black-box-discover: `python3 harness/po_replay.py mill DIR` and `python3 harness/po_replay.py sps DIR`.
- **iOS** — `MainViewController()` in `iosMain` (framework `MsePlayground`); host app is local, not in git
- **Android** — `./gradlew :playground-android:installDebug` (needs an emulator/device and
  `ffmpeg-build/out/android-<abi>`)

## Layout

- `commonMain` — `YtMsePlayerScreen`, `MseNativeVideo` expect, session expect
- `jvmMain` — SwingPanel blit (`contains() = false` so clicks stay in Compose)
- `iosMain` — UIKitView + UIImageView (`isInteractive = false`)
- `androidMain` — TextureView + ANativeWindow (`setVideoSurface`)
- `:playground-android` — thin AGP application shell (AGP 9 cannot combine `application` with KMP)
