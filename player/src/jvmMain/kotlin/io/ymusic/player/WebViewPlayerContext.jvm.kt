package io.ymusic.player

import io.ymusic.ytmedia.proxy.LocalMediaProxy

/**
 * JVM desktop no longer hosts a Chromium WebView. YouTube playback is [io.ymusic.player.mse.MsePlayer].
 * This type remains so [createWebViewPlayer] still compiles; calling it throws.
 */
actual class WebViewPlayerContext(
    val mediaProxy: LocalMediaProxy? = null,
)
