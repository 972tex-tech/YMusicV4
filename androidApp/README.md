# `:androidApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **What it is** — the Android product shell: plain AGP 9 application module booting the same shared `App` over ExoPlayer + Android WebView engines.
  - **`com.android.application` cannot combine with the KMP plugin since AGP 9** — plain layout `src/main/`, built-in Kotlin, `org.jetbrains.compose` plugin applied for dependency substitution.

- **compileSdk** — 36 (okhttp 5.4 demands it for app modules).

- **Assets** — `webview_ymusic.js` rides as an asset (absent → YouTube items surface E60050).

- **Not wired yet** — media store/SAF scanner.
