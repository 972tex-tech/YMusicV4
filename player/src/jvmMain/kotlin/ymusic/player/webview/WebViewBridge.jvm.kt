package ymusic.player.webview

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.ImageOsrBrowser
import org.cef.callback.CefCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import ymusic.player.WebViewPlayerContext
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * JVM desktop [WebViewBridge] on JCEF (Chromium). CEF has no load-with-base-URL, so the injected HTML
 * is served **at** the embed URL by a resource handler (the request-interception model, like Android's
 * `shouldInterceptRequest`) so `document.origin` is `youtube.com`. Page → host messages use a
 * `CefMessageRouter` (`window.cefQuery`); the bundle's `postMsg` reaches it via the [bootPrelude]
 * shim that defines `window.ymixChannelAndroid` on top of `cefQuery`. `onQuery` arrives on a CEF
 * thread, so it re-posts to the AWT event thread (the engine's main thread).
 */
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge = JcefWebViewBridge(context, sink)

private const val EMBED_URL = "https://www.youtube.com/embed"

/** Internal channel the browser posts its `navigator.userAgent` back on (see [JcefWebViewBridge]). */
private const val UA_CHANNEL = "__ua"

/** Chrome-family fallback used only until the real UA round-trips from the page. */
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/150.0.0.0 Safari/537.36"

/** Any `*.js` URL (optionally with `?`/`#` suffix) — blocked unless it carries [DO_NOT_BLOCK]. */
private val JS_URL_REGEX = Regex(".*\\.js([?#].*)*$")
private const val DO_NOT_BLOCK = "_doNotBlock=42"

private class JcefWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
) : WebViewBridge {
    override val platform: String = "jvm"

    // Shim the transport the bundle's `postMsg` expects onto JCEF's cefQuery. Channel names carry no
    // '|', so it is a safe separator (same convention as the iOS bridge).
    //
    // Every JCEF channel that hands Java a String funnels through NewJNIString -> JNI
    // NewStringUTF, which takes modified UTF-8 while CefString supplies real UTF-8: each astral
    // character costs one UTF-16 unit off the END of the message (126.2.0, measured on cefQuery and
    // on console messages alike), which clips the closing braces off a 200 KB payload and makes it
    // unparseable. Escaping keeps the request pure ASCII. It is sound only because every payload
    // here is JSON text, where `\uXXXX` decodes back to the same string — a raw non-JSON payload
    // would arrive with the escapes still in it.
    override val bootPrelude: String =
        """window.ymixChannelAndroid={postMessage:function(c,m){try{""" +
            """var s=(typeof m==='string'?m:JSON.stringify(m)).replace(/[\u0080-\uffff]/g,""" +
            """function(x){return '\\u'+(x.charCodeAt(0)+65536).toString(16).substring(1);});""" +
            """window.cefQuery({request:c+'|'+s,""" +
            """persistent:false,onSuccess:function(){},onFailure:function(){}});}catch(e){}},""" +
            """remoteDebug:function(){}}"""

    private val client = context.cefClient
    private val surface = context.surface

    @Volatile private var pendingHtml: String? = null

    private val router = CefMessageRouter.create().apply {
        addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: CefQueryCallback?,
                ): Boolean {
                    val msg = request ?: return false
                    val sep = msg.indexOf('|')
                    if (sep >= 0) {
                        val channel = msg.substring(0, sep)
                        val payload = msg.substring(sep + 1)
                        if (channel == UA_CHANNEL) {
                            cachedUserAgent = payload
                        } else {
                            SwingUtilities.invokeLater { sink.onMessage(channel, payload) }
                        }
                    }
                    callback?.success("")
                    return true
                }
            },
            true,
        )
    }

    private val requestHandler = object : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler? {
            val url = request?.url ?: return null
            // Block any *.js the content-script hook didn't route through the transform pipeline (which
            // appends _doNotBlock=42) — the un-transformed player must not run (matches Android + legacy).
            if (url.matches(JS_URL_REGEX) && !url.contains(DO_NOT_BLOCK)) {
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?) =
                        BlockedResourceHandler()
                }
            }
            val html = pendingHtml ?: return null
            // Only the initial main-frame navigation to the embed URL is served the injected HTML;
            // YouTube's later /watch navigations fall through to the network.
            if (isNavigation && (url == EMBED_URL || url == "$EMBED_URL/")) {
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(
                        browser: CefBrowser?,
                        frame: CefFrame?,
                        request: CefRequest?,
                    ) = StringResourceHandler(html)
                }
            }
            return null
        }
    }

    private val browser: CefBrowser

    // A *windowed* JCEF browser is created natively only once its UI component is realized (added to
    // a showing window), signalled by onAfterCreated. loadURL before that is dropped, so gate it: a
    // navigation requested earlier is held in [pendingUrl] and replayed when the browser is created.
    @Volatile private var browserCreated = false
    private var pendingUrl: String? = null // EDT-confined

    private val lifeSpanHandler = object : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(created: CefBrowser?) {
            SwingUtilities.invokeLater {
                browserCreated = true
                // Capture the JCEF browser's real UA (about:blank has navigator.userAgent) so the embed
                // fetch matches the WebView's own requests — BotGuard/attestation needs one consistent
                // client identity. Round-trips via cefQuery into [UA_CHANNEL]; [DEFAULT_USER_AGENT]
                // covers the brief window before it lands.
                browser.executeJavaScript(
                    "window.cefQuery({request:'$UA_CHANNEL|'+navigator.userAgent," +
                        "persistent:false,onSuccess:function(){},onFailure:function(){}})",
                    browser.url ?: EMBED_URL,
                    0,
                )
                pendingUrl?.let { url ->
                    pendingUrl = null
                    browser.loadURL(url)
                }
            }
        }
    }

    @Volatile private var cachedUserAgent: String = DEFAULT_USER_AGENT
    override val userAgent: String get() = cachedUserAgent

    init {
        client.addLifeSpanHandler(lifeSpanHandler)
        client.addMessageRouter(router)
        client.addRequestHandler(requestHandler)
        // Off-screen rendering into a lightweight component: real z-order under Compose, and the
        // page always sees a real-size viewport regardless of how small the UI draws the surface
        // (needs windowless_rendering_enabled on CefApp — the shell's CefManager sets it).
        browser = ImageOsrBrowser(client, "about:blank", surface)
        browser.createImmediately()
    }

    override fun load(html: String, baseUrl: String) {
        pendingHtml = html
        if (browserCreated) browser.loadURL(baseUrl) else pendingUrl = baseUrl
    }

    override fun setVisible(visible: Boolean) {
        surface.setVisible(visible)
    }

    override fun evaluate(js: String) {
        browser.executeJavaScript(js, browser.url ?: EMBED_URL, 0)
    }

    override fun release() {
        surface.setVisible(false)
        client.removeRequestHandler()
        client.removeMessageRouter(router)
        client.removeLifeSpanHandler()
        router.dispose()
        browser.close(true)
    }
}

/** Serves a fixed HTML string as a CEF resource (200 text/html). */
private class StringResourceHandler(html: String) : CefResourceHandlerAdapter() {
    private val data = html.encodeToByteArray()
    private var offset = 0

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        response?.let {
            it.mimeType = "text/html"
            it.status = 200
            // The injected boot script runs before the document's own <meta charset>, so without this
            // the parser decodes it as windows-1252 and any non-ASCII in the bundle is mojibake.
            it.setHeaderByName("Content-Type", "text/html; charset=utf-8", true)
        }
        responseLength?.set(data.size)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        if (dataOut == null || offset >= data.size) {
            bytesRead?.set(0)
            return false
        }
        val n = min(bytesToRead, data.size - offset)
        System.arraycopy(data, offset, dataOut, 0, n)
        offset += n
        bytesRead?.set(n)
        return true
    }
}

/** Serves an empty `404` — used to block un-transformed `*.js` (see the request handler). */
private class BlockedResourceHandler : CefResourceHandlerAdapter() {
    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        response?.let {
            it.mimeType = "text/plain"
            it.status = 404
        }
        responseLength?.set(0)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        bytesRead?.set(0)
        return false
    }
}
