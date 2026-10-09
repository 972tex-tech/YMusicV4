# `:desktopApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Run** — the JVM desktop product shell: `./gradlew :desktopApp:run`.

- **Boot sequence** — boots:
  - `DynamicEngineManager` (QuickJS + engine-js)
  - a file-backed `:media-store` + `:media-scanner` "Scan folder…" (packed ffmpeg, or `-Dio.ymusic.ffmpeg` / PATH)
  - assembles the facade over ffmpeg local + MSE YouTube, injects the SwingPanel MSE surface.

- **Cookies** — `PersistentCookieJar` JSON file under `~/.ymusic/api-cookies.json`. No Chromium. Sign-in is owned here (`DesktopCookieSignIn` via `AppGraph.cookieSignIn`): scan a QR from the phone app (Settings → Link devices; matching confirmation code + phone unlock), or paste a Copy as cURL dump from Chrome / Firefox / Safari Network. The encrypted handoff goes through the YMusic service's one-shot `/v1/link` slot, deleted as the desktop reads it.

- **Assets** — needs the built `engine-js` bundle in the jar.
