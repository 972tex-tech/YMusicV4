# iosApp — the shared Compose UI on iOS

The iOS product shell: runs the **same** shared Compose `App` (`:commonUi`) as the desktop and Android
shells, on a real iPhone/iPad, over the AVFoundation local + WKWebView YouTube engines. Symmetric with
`:desktopApp` and `:androidApp`.

`iosApp/iOSApp.swift` (SwiftUI `@main`) hosts `MainViewControllerKt.MainViewController()` from the
`:commonUi` **YMusicApp** framework, which builds the iOS `AppGraph` and runs `App(graph)`.

## Status — blocked on Xcode 16 (not on code)

The whole Kotlin chain (`:quickjs` / `:media-store` / `:dynamic-engine` / `:player` + the shared Compose
UI) **compiles for `iosArm64`** on this Intel host. It does **not link** under **Xcode 15.3**: Compose
Multiplatform 1.11.1's `ui-uikit` references `UIViewLayoutRegion` (an **iOS 18** UIKit class), absent
from Xcode 15.3's iOS 17.4 SDK — the link fails with:

```
Undefined symbols for architecture arm64:
  "_OBJC_CLASS_$_UIViewLayoutRegion", referenced from: … ui-uikit … CMPLayoutRegion.o
```

**Fix: install Xcode 16+ (iOS 18 SDK).** This Mac (macOS 14.8) supports Xcode 16.0–16.2. The Kotlin
compiles fine on any Xcode; only the framework *link* needs the iOS 18 SDK. (Alternatively, CI on a
`macos-15` runner with Xcode 16 links it today.)

## Run on a device (once Xcode 16 is installed)

1. `ruby gen_xcodeproj.rb` (regenerates `iosApp.xcodeproj`; needs the `xcodeproj` gem)
2. `open iosApp.xcodeproj`
3. Target **iosApp** → **Signing & Capabilities** → pick your **Team** (change the bundle id if
   `com.ymusic.iosapp` is taken).
4. Plug in + unlock + **Trust** the device; select it as the run destination.
5. **Run** (⌘R). First launch: **Settings → General → VPN & Device Management** → trust the profile.

A Gradle build phase runs `:commonUi:embedAndSignAppleFrameworkForXcode -PkmpTargets=all` to build +
embed + sign `YMusicApp.framework` for the targeted SDK/arch. `-PkmpTargets=all` is what configures
`iosArm64` on this Intel host; `JAVA_HOME` is set to `$HOME/.sdkman/candidates/java/current`.

Signing is the only manual step — this environment can't enter your Apple ID; Xcode does it locally.

## Notes

- `mediaStore` is null in the iOS graph for now (Library shows empty) — keeps the shell lean; the
  media-store SQLDelight native driver can be wired later (it already compiles for `iosArm64`).
- Local video isn't hosted in the shared surface on iOS yet (AVFoundation renders via `AVPlayerLayer`
  in the player-lab spike); the shared surface hosts the WebView. Audio (the music default) is fine.
