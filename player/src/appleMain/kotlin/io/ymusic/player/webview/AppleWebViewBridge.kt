package io.ymusic.player.webview

import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKContentWorld
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.javaScriptEnabled
import platform.darwin.NSObject

/**
 * The User-Agent the Apple WKWebView and every request made on its behalf must share (the embed fetch
 * and the player-cache proxy's `/videoplayback` forward) — BotGuard/attestation rejects a mismatched
 * client identity. Public so the app can build the proxy's [LocalMediaProxy] client with it.
 */
const val APPLE_WEBVIEW_USER_AGENT: String =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

/**
 * The WKWebView half of the Apple [WebViewBridge], shared by iOS and macOS (WKWebView is a `UIView`
 * subclass on iOS, an `NSView` subclass on macOS). The container-specific attach — the differing
 * `UIView`/`NSView` type — is done by the per-leaf `createWebViewBridge` actuals, which add
 * [nativeView] to their container. The page posts to `webkit.messageHandlers.ymixChanelIos` (the
 * typo is intentional — it matches the injected bundle); WKScriptMessageHandler callbacks arrive on
 * the main thread, as the engine requires.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleWebViewBridge(
    sink: WebViewMessageSink,
    mediaProxy: LocalMediaProxy? = null,
    configure: WKWebViewConfiguration.() -> Unit = {},
) : WebViewBridge {
    override val platform: String = "darwin"

    override val playerCacheEnabled: Boolean = mediaProxy != null

    private val handler = MessageHandler(sink)

    // The player-cache delivery channel: WKWebView can't intercept https, so webview-js on darwin serves
    // /videoplayback over this WKScriptMessageHandlerWithReply channel (see AppleProxyChannel). Only
    // installed when a cache is wired; a with-reply handler must register in a specific content world.
    private val proxyChannel = mediaProxy?.let { AppleProxyChannel(it) }

    private val config = WKWebViewConfiguration().apply {
        preferences.javaScriptEnabled = true
        websiteDataStore = WKWebsiteDataStore.defaultDataStore()
        userContentController.addScriptMessageHandler(handler, HANDLER_NAME)
        proxyChannel?.let {
            userContentController.addScriptMessageHandlerWithReply(
                scriptMessageHandlerWithReply = it,
                contentWorld = WKContentWorld.pageWorld,
                name = PROXY_HANDLER_NAME,
            )
        }
        configure()
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

    override fun setVisible(visible: Boolean) {
        nativeView.hidden = !visible
        if (visible) nativeView.superview?.addSubview(nativeView)
    }

    override fun evaluate(js: String) {
        nativeView.evaluateJavaScript(js, null)
    }

    override fun release() {
        config.userContentController.removeScriptMessageHandlerForName(HANDLER_NAME)
        proxyChannel?.let {
            config.userContentController.removeScriptMessageHandlerForName(PROXY_HANDLER_NAME, WKContentWorld.pageWorld)
            it.release()
        }
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

        // The player-cache channel webview-js posts to on darwin (see AppleProxyChannel / skip_ads.ts).
        const val PROXY_HANDLER_NAME = "ymusicProxy"

        // Both the WKWebView and the embed fetch use this, so the client identity is consistent for
        // BotGuard/attestation. Shared with the player-cache proxy via [APPLE_WEBVIEW_USER_AGENT].
        const val USER_AGENT = APPLE_WEBVIEW_USER_AGENT

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
