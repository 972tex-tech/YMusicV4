# `:playground`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **What it is** — a Compose Multiplatform demo app to hand-check `:player`.
  - Shared `PlayerScreen` (open file → play/pause/seek/±10s/volume/speed, an **Audio-only ↔ Video toggle**, and a
    `PlayerSurface` video area that shows the picture in video mode) in `commonMain`, reused across targets.
  - `PlayerSurface` is `expect`/`actual`; the Desktop `actual` paints `LocalPlayer.localVideoSurface()`'s off-screen
    frames as a Compose `Image` (no `SwingPanel` — VLCJ renders off-screen, like the JCEF WebView).

- **Run (Desktop-only today)** — currently wired for **Desktop (JVM)** only.
  - The runnable check on this host: `./gradlew :playground:run` opens a window and plays video+audio via the VLCJ
    backend (needs VLC/libVLC installed; found at `/Applications/VLC.app` here).
  - Note: Compose MP has **no native-macOS UI target** — its "desktop" is always the JVM, so this exercises VLCJ, not
    AVFoundation; the native Apple backend is checked by the SwiftUI spike below.

- **Compose MP version wiring** — Compose MP `1.11.1` (compiler = Kotlin-bundled `plugin.compose`).
  - The `org.jetbrains.compose` plugin version comes from the **root** `build.gradle.kts` plugins block
    (`Versions.composeMultiplatform`) — do NOT also add it to the buildSrc classpath (double-declaration →
    plugin-resolution conflict).

- **Follow-ups** — Android/iOS player-screen apps are a straightforward follow-up (each needs its own app module /
  Xcode entry).

- **Native Apple checks** *(SwiftUI spike apps, not published)* — macOS and iOS spikes over `:player`'s exported
  `Player.framework` drive BOTH Apple engines host-native, no Compose: **Local** = the AVFoundation `LocalPlayer`
  (`AVPlayerLayer` video + transport + audio-only), **YouTube** = the WKWebView `WebViewPlayer` (shown via
  `NSViewRepresentable`/`UIViewRepresentable` over the container the player injects into). They are the way to
  hand-check the WKWebView engine on a Mac without an iOS simulator.
  - Swift gotcha worth keeping: the exported facade protocol `Player` (swift_name `Player`) collides with the
    framework module name `Player`, so `Player.Progress` mis-binds — read `Progress` fields off the inferred
    `currentProgress()` value instead of naming the type.
