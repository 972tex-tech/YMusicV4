package ymusic.player.webview

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import ymusic.player.WebViewPlayerContext

@OptIn(ExperimentalForeignApi::class)
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge {
    val bridge = AppleWebViewBridge(sink) {
        allowsInlineMediaPlayback = true
        allowsPictureInPictureMediaPlayback = false
    }
    val view = bridge.nativeView
    view.setFrame(context.container.bounds)
    view.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
    context.container.addSubview(view)
    return bridge
}
