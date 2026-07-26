package ymusic.player.webview

import okio.Buffer
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
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import ymusic.player.WebViewPlayerContext
import java.net.URLDecoder
import java.util.Vector
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

/** Chrome-family fallback (JCEF 126 = Chrome 126) used only until the real UA round-trips from the page. */
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

/**
 * Same-origin endpoint the page POSTs its messages to (see [JcefWebViewBridge.bootPrelude]). It is
 * served by this bridge and never reaches the network; same-origin so the page needs no CORS grant.
 */
private const val MSG_PATH = "https://www.youtube.com/__ymix_msg"

/** Any `*.js` URL (optionally with `?`/`#` suffix) — blocked unless it carries [DO_NOT_BLOCK]. */
private val JS_URL_REGEX = Regex(".*\\.js([?#].*)*$")
private const val DO_NOT_BLOCK = "_doNotBlock=42"

private class JcefWebViewBridge(
    context: WebViewPlayerContext,
    private val sink: WebViewMessageSink,
) : WebViewBridge {
    override val platform: String = "jvm"

    // Shim the transport the bundle's `postMsg` expects onto a same-origin POST this bridge's
    // resource handler claims (MSG_PATH) — NOT cefQuery.
    //
    // Every JCEF channel that hands Java a String goes through NewJNIString -> JNI NewStringUTF,
    // which wants modified UTF-8 while CefString supplies real UTF-8: each astral character costs
    // one UTF-16 unit off the END of the message (measured on cefQuery and on console messages
    // alike, 126.2.0), so a 200 KB payload whose tail is closing JSON braces arrives unparseable.
    // A POST body never becomes a native String — it comes back as bytes and is decoded here — so
    // the payload survives whatever is in it. The channel name still crosses as a String, in the
    // query, which is safe because channel names are ASCII.
    //
    // The sends are chained through one promise: parallel fetches would let a state update
    // overtake the one before it.
    override val bootPrelude: String =
        """window.__ymixQ=Promise.resolve();""" +
            """window.ymixChannelAndroid={postMessage:function(c,m){try{""" +
            """var s=(typeof m==='string'?m:JSON.stringify(m));""" +
            """window.__ymixQ=window.__ymixQ.then(function(){""" +
            """return fetch('""" + MSG_PATH + """?c='+encodeURIComponent(c),""" +
            """{method:'POST',body:s,cache:'no-store'}).catch(function(){});""" +
            """});}catch(e){}},remoteDebug:function(){}}"""

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

    /**
     * One page message: the channel from the query (ASCII), the payload from the POST body as raw
     * bytes. [CefPostDataElement.getBytes] is the whole point — nothing here crosses as a native
     * String, so the astral-character loss that afflicts every String-carrying JCEF channel cannot
     * touch the payload.
     */
    private fun deliver(url: String, request: CefRequest) {
        val channel = url.substringAfter("?c=", "").substringBefore('&').let(::decodeQueryComponent)
        if (channel.isEmpty()) return
        val data = request.postData ?: return
        val elements = Vector<CefPostDataElement>()
        data.getElements(elements)
        val body = Buffer()
        for (element in elements) {
            val size = element.bytesCount
            if (size <= 0) continue
            val bytes = ByteArray(size)
            val read = element.getBytes(size, bytes)
            body.write(bytes, 0, read)
        }
        val payload = body.readUtf8()
        SwingUtilities.invokeLater { sink.onMessage(channel, payload) }
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
            if (url.startsWith(MSG_PATH)) {
                deliver(url, request)
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?) =
                        AcceptedResourceHandler()
                }
            }
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

/** Serves an empty `204` — the ack for a delivered page message (see [MSG_PATH]). */
private class AcceptedResourceHandler : CefResourceHandlerAdapter() {
    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        response?.let {
            it.mimeType = "text/plain"
            it.status = 204
            it.setHeaderByName("Cache-Control", "no-store", true)
        }
        responseLength?.set(0)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        bytesRead?.set(0)
        return false
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

/** Percent-decoding for the channel query value (UTF-8, `+` is a literal plus in a query value). */
private fun decodeQueryComponent(value: String): String =
    runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
