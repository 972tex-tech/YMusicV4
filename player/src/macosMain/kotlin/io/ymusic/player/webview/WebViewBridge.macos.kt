package io.ymusic.player.webview

import io.ymusic.player.WebViewPlayerContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable

@OptIn(ExperimentalForeignApi::class)
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge {
    val bridge = AppleWebViewBridge(sink, context.mediaProxy)
    val view = bridge.nativeView
    view.setFrame(context.container.bounds)
    view.setAutoresizingMask(NSViewWidthSizable or NSViewHeightSizable)
    context.container.addSubview(view)
    return bridge
}
