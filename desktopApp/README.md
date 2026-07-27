# `:desktopApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

the JVM desktop product shell: `./gradlew :desktopApp:run`. Boots JCEF (`CefManager`, bundle under `~/.ymusic/`), `DynamicEngineManager`, a file-backed `:media-store` + `:media-scanner` "Scan folder…" (ffmpeg via `YMUSIC_FFMPEG`/PATH), assembles the facade over VLCJ + JCEF WebView engines, injects the SwingPanel surface. Needs the built `webview-js` bundle on disk. **`:desktopApp:webviewProbe`** (`-PprobeArgs="realized|headless|tiny|small|audioonly|reparent"`) is a headless diagnostic that drives the WebView engine and reports whether playback advances — the regression guard for the "windowed WebView must be realized to play" fix.
