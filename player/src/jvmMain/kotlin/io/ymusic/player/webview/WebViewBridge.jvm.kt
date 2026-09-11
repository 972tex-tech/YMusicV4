package io.ymusic.player.webview

import io.ymusic.player.WebViewPlayerContext

internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
    logger: kmpcommon.log.Logger?,
): WebViewBridge = error("Desktop YouTube playback is MsePlayer; JCEF was removed")
