package ymusic.player.webview

import kmpcommon.error.TechnicalException
import ymusic.player.WebViewPlayerContext

/** JCEF host — planned (ymix-client experiment as the base). Throws until then. */
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge = throw TechnicalException("E60040", "JVM WebView host not implemented yet")
