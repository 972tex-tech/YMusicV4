package ymusic.player.webview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKContentRuleListStore
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

    /**
     * The WebKit view; a `UIView`/`NSView` the leaf actual injects into the caller's container. Born
     * at a real 320×180 (not 0×0) so the page's viewport is never 0×0 — a 0-size webview reports
     * `innerWidth/innerHeight == 0` and gets throttled/paused. Once attached the leaf resizes it to the
     * container; a headless host keeps a real size and moves the window off-screen instead. Verified on
     * macOS WKWebView (window off-screen keeps `visibilityState:"visible"`; 0×0 / occlusion / alpha=0
     * do not).
     */
    // One consistent client identity: set the WKWebView's UA and use the SAME value for the embed fetch,
    // so the WebView's own requests and the fetch match — BotGuard/attestation requires it. WKWebView
    // exposes no synchronous default-UA getter in Kotlin/Native, so we control the UA rather than read it.
    override val userAgent: String = USER_AGENT

    val nativeView: WKWebView =
        WKWebView(frame = CGRectMake(0.0, 0.0, 320.0, 180.0), configuration = config)
            .apply { customUserAgent = USER_AGENT }

    init {
        // Block any *.js the content-script hook didn't route through the transform pipeline (which
        // appends _doNotBlock=42) — the un-transformed player must not run (matches Android + JCEF).
        // WKContentRuleList compiles async; it's applied well before the first load().
        WKContentRuleListStore.defaultStore()?.compileContentRuleListForIdentifier(
            "ymixBlockUntransformedJs",
            JS_BLOCK_RULE_JSON,
        ) { list, _ ->
            if (list != null) config.userContentController.addContentRuleList(list)
        }
    }

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

        // A current macOS Safari UA (the active Apple target; iOS is a spike). Both the WKWebView and the
        // embed fetch use it, so the client identity is consistent for BotGuard/attestation.
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

        // Content-rule-list JSON: block every *.js, then ignore-previous for any URL carrying
        // _doNotBlock=42 (the marker the content-script hook appends to its transformed re-fetches).
        val JS_BLOCK_RULE_JSON: String = buildJsonArray {
            addJsonObject {
                putJsonObject("trigger") { put("url-filter", ".*\\.js(\\?.*)*$") }
                putJsonObject("action") { put("type", "block") }
            }
            addJsonObject {
                putJsonObject("trigger") { put("url-filter", "_doNotBlock=42") }
                putJsonObject("action") { put("type", "ignore-previous-rules") }
            }
        }.toString()
    }
}
