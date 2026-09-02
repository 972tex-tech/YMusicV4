# `:playground`

Compose Multiplatform **MSE YouTube** lab: engine-js `DynamicEnginePlayer` + C leftover/ffmpeg.
One screen (`YtMsePlayerScreen`) on JVM, iOS, and Android. Video paints in a native hole
(SwingPanel / UIKitView / TextureView); Compose chrome does not recompose each frame.

## Run

- **Desktop JVM** — `./gradlew :playground:run`
- **iOS** — `MainViewController()` in `iosMain` (framework `MsePlayground`); host app is local, not in git
- **Android** — `./gradlew :playground-android:installDebug` (needs an emulator/device and
  `ffmpeg-build/out/android-<abi>`)

## Layout

- `commonMain` — `YtMsePlayerScreen`, `MseNativeVideo` expect, session expect
- `jvmMain` — SwingPanel blit (`contains() = false` so clicks stay in Compose)
- `iosMain` — UIKitView + UIImageView (`isInteractive = false`)
- `androidMain` — TextureView + ANativeWindow (`setVideoSurface`)
- `:playground-android` — thin AGP application shell (AGP 9 cannot combine `application` with KMP)
