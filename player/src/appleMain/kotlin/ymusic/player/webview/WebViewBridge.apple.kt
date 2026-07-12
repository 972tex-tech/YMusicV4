package ymusic.player.webview

import kmpcommon.error.TechnicalException
import ymusic.player.WebViewPlayerContext

/** WKWebView host — implemented in the next increment (WKScriptMessageHandler + loadHTMLString). */
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge = throw TechnicalException("E60040", "WKWebView host not implemented yet")
