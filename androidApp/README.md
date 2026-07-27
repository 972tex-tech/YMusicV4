# `:androidApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

the Android product shell: plain AGP 9 application module (**`com.android.application` cannot combine with the KMP plugin since AGP 9** — plain layout `src/main/`, built-in Kotlin, `org.jetbrains.compose` plugin applied for dependency substitution) booting the same shared `App` over ExoPlayer + Android WebView engines. compileSdk 36 (okhttp 5.4 demands it for app modules). `webview_ymusic.js` rides as an asset (absent → YouTube items surface E60050); media store/SAF scanner not wired yet.
