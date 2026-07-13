package ymusic.player.webview

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefCallback
import org.cef.callback.CefQueryCallback
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

private class JcefWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
) : WebViewBridge {
    override val platform: String = "jvm"

    // Shim the transport the bundle's `postMsg` expects onto JCEF's cefQuery. Channel names carry no
    // '|', so it is a safe separator (same convention as the iOS bridge).
    override val bootPrelude: String =
        "window.ymixChannelAndroid={postMessage:function(c,m){try{window.cefQuery({" +
            "request:c+'|'+(typeof m==='string'?m:JSON.stringify(m))," +
            "persistent:false,onSuccess:function(){},onFailure:function(){}});}catch(e){}}," +
            "remoteDebug:function(){}}"

    private val client = context.cefClient
    private val container = context.container

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
                        SwingUtilities.invokeLater { sink.onMessage(channel, payload) }
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
            val html = pendingHtml ?: return null
            val url = request?.url ?: return null
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

    init {
        client.addMessageRouter(router)
        client.addRequestHandler(requestHandler)
        browser = client.createBrowser("about:blank", false, false)
        container.add(browser.uiComponent)
    }

    override fun load(html: String, baseUrl: String) {
        pendingHtml = html
        browser.loadURL(baseUrl)
    }

    override fun evaluate(js: String) {
        browser.executeJavaScript(js, browser.url ?: EMBED_URL, 0)
    }

    override fun setVideoVisible(visible: Boolean) {
        val c = browser.uiComponent
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            c.isVisible = visible
        } else {
            javax.swing.SwingUtilities.invokeLater { c.isVisible = visible }
        }
    }

    override fun release() {
        container.remove(browser.uiComponent)
        client.removeRequestHandler()
        client.removeMessageRouter(router)
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
