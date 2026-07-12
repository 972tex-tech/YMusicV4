package ymusic.player.webview

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.javaScriptEnabled
import platform.darwin.NSObject

/**
 * The WKWebView half of the Apple [WebViewBridge], shared by iOS and macOS (WKWebView is a `UIView`
 * subclass on iOS, an `NSView` subclass on macOS). The container-specific attach — the differing
 * `UIView`/`NSView` type — is done by the per-leaf `createWebViewBridge` actuals, which add
 * [nativeView] to their container. The page posts to `webkit.messageHandlers.ymixChanelIos` (the
 * typo is intentional — it matches the injected bundle); WKScriptMessageHandler callbacks arrive on
 * the main thread, as the engine requires.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleWebViewBridge(sink: WebViewMessageSink) : WebViewBridge {
    override val platform: String = "darwin"

    private val handler = MessageHandler(sink)
    private val config = WKWebViewConfiguration().apply {
        preferences.javaScriptEnabled = true
        userContentController.addScriptMessageHandler(handler, HANDLER_NAME)
    }

    /** The WebKit view; a `UIView`/`NSView` the leaf actual injects into the caller's container. */
    val nativeView: WKWebView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)

    override fun load(html: String, baseUrl: String) {
        nativeView.loadHTMLString(html, NSURL(string = baseUrl))
    }

    override fun evaluate(js: String) {
        nativeView.evaluateJavaScript(js, null)
    }

    override fun release() {
        config.userContentController.removeScriptMessageHandlerForName(HANDLER_NAME)
        nativeView.removeFromSuperview()
    }

    private class MessageHandler(
        private val sink: WebViewMessageSink,
    ) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            val msg = didReceiveScriptMessage.body as? String ?: return
            val sep = msg.indexOf('|')
            if (sep < 0) return
            sink.onMessage(msg.substring(0, sep), msg.substring(sep + 1))
        }
    }

    private companion object {
        // Matches webkit.messageHandlers.ymixChanelIos in the injected bundle (the single 'n' is the
        // bundle's spelling).
        const val HANDLER_NAME = "ymixChanelIos"
    }
}
