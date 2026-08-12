package io.ymusic.player.webview

import io.ymusic.player.WebViewPlayerContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

@OptIn(ExperimentalForeignApi::class)
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
    logger: kmpcommon.log.Logger?,
): WebViewBridge {
    val bridge = AppleWebViewBridge(sink, context.mediaProxy, logger) {
        allowsInlineMediaPlayback = true
        allowsPictureInPictureMediaPlayback = false
    }
    val view = bridge.nativeView
    view.setFrame(context.container.bounds)
    view.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
    context.container.addSubview(view)
    return bridge
}
