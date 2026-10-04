# ios-player-lab

A native **SwiftUI iOS app** that drives `:player` through the exported `Player.framework` in the
**iOS Simulator** — no Compose. The iOS counterpart of `spikes/macos-player-lab`, and the runnable
proof that the shared engine stack builds and runs on iOS **from an Intel Mac**.

Why it exists: this dev host is Intel (x86_64), so the only iOS target it can build/run is `iosX64`
(the Intel simulator). Compose Multiplatform 1.11.1 dropped `iosX64`, so the *shared Compose UI*
can't build here — but `:player` (and its deps: `:kmpHttp`, the Apple AVPlayer local engine)
has no Compose dependency and ships `iosX64`, so a native shell over the framework runs fine.

- **Local** — the AVFoundation `LocalPlayer` playing a bundled `sample.mp4` (`AVPlayerLayer` video +
  transport + audio-only). Verified: `state=Ready`, `video=320x180`, position advances to `Ended`.

Muted by default (config volume 0) — unmute with the slider.

## Run on a real device (Xcode) — arm64

`PlayerLab.xcodeproj` installs to a physical iPhone/iPad from this Intel Mac (Kotlin/Native
cross-compiles `iosArm64`; Compose isn't involved here, so the x64-simulator limitation doesn't apply).

1. `open PlayerLab.xcodeproj`
2. Target **PlayerLab** → **Signing & Capabilities** → pick your **Team** (Xcode auto-manages the
   provisioning profile; change the bundle id if `com.ymusic.iosplayerlab` is taken).
3. Plug in the device, unlock it, tap **Trust**. Select it as the run destination.
4. **Run** (⌘R). First launch: on the device, **Settings → General → VPN & Device Management** →
   trust your developer profile.

A Gradle build phase compiles + embeds + signs `Player.framework` for the SDK/arch Xcode targets
(`:player:embedAndSignAppleFrameworkForXcode -PkmpTargets=all` — `-PkmpTargets=all` so `iosArm64` is
configured on this Intel host). Regenerate the project after changing sources/resources/settings with
`ruby gen_xcodeproj.rb` (the `.xcodeproj` is generated, not hand-edited). It needs Java on PATH; the
phase sets `JAVA_HOME=$HOME/.sdkman/candidates/java/current`.

Signing is the only manual step — this environment can't enter your Apple ID; Xcode does it locally.

## Run in the Intel Simulator (script)

```
./build.sh            # framework → swiftc (iphonesimulator) → .app → boot sim → install → launch
./build.sh --no-run   # build + bundle only
```

Requires Xcode + an available iPhone simulator; `ffmpeg` on PATH to generate the sample clip. The
build links `player/build/bin/iosX64/debugFramework/Player.framework` (`:player:linkDebugFrameworkIosX64`),
compiles `Sources/PlayerLab.swift` for `x86_64-apple-ios*-simulator`, assembles a flat `.app`, and
launches it via `simctl` (streaming the app log — the `[ios-lab]` lines show playback advancing).

Note: the shared Compose UI (`:commonUi`) targets `iosArm64`/`iosSimulatorArm64` only (Compose 1.11.1
has no `iosX64`), so it builds on Apple Silicon / CI (`macos-14`), not on this Intel host.
