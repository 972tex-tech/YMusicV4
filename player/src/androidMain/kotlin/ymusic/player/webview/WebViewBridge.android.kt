package ymusic.player.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ymusic.player.WebViewPlayerContext
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Android [WebViewBridge]: a `WebView` in the caller's container, the `ymixChannelAndroid` JS
 * interface for page → host posts, `loadDataWithBaseURL` for the unified fetch→inject→load path, and
 * `evaluateJavascript` for commands. The JS interface delivers on a binder thread, so it re-posts to
 * the main looper before touching the engine (which the [ymusic.player.MediaEngine] contract runs on
 * the main thread).
 */
internal actual fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge = AndroidWebViewBridge(context.context, context.container, sink)

@SuppressLint("SetJavaScriptEnabled")
private class AndroidWebViewBridge(
    androidContext: android.content.Context,
    private val container: ViewGroup,
    sink: WebViewMessageSink,
) : WebViewBridge {

    override val platform: String = "android"

    // The bundle reads window.androidOsVer for OS-version-gated behavior; ymixChannelAndroid is the
    // native JS interface (added below), so no transport shim is needed here.
    override val bootPrelude: String = "window.androidOsVer=${android.os.Build.VERSION.SDK_INT}"

    private val mainHandler = Handler(Looper.getMainLooper())

    // A WebView that always reports itself VISIBLE: Chromium throttles/suspends the media pipeline
    // when the WebView is occluded or off-window (a small docked surface, another screen on top, the
    // app backgrounded), which stalls SABR mid-stream — the buffer runs out ~60s in and YouTube shows
    // "An error occurred". Forcing visibility keeps decoding + segment fetches alive (legacy YMusic
    // does the same). See [[webview-must-be-realized-to-play]].
    private val webView = object : WebView(androidContext) {
        override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(VISIBLE)
        override fun getWindowVisibility(): Int = VISIBLE
        public override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
        }
    }.apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(JsInterface(mainHandler, sink), "ymixChannelAndroid")
        // Seed a non-zero, slightly-randomized size (like legacy WebViewYtPlayer) so the page viewport
        // is never 0×0 before real layout — a 0-size webview reports innerWidth/innerHeight == 0 and gets
        // throttled/paused — and so it doesn't report a fixed 1024×500 fingerprint. ~300×200 dp + jitter.
        val density = resources.displayMetrics.density
        onSizeChanged(
            ((300f + 20f * Random.nextFloat()) * density).toInt(),
            ((200f + 10f * Random.nextFloat()) * density).toInt(),
            0,
            0,
        )
        webViewClient = WebViewClientImpl()
        webChromeClient = WebChromeClientImpl()
    }

    override val userAgent: String = webView.settings.userAgentString

    init {
        // The YouTube embed is cross-origin to googlevideo.com and the auth/session hosts, so its
        // session cookies are third-party from the page's view — enable them on THIS WebView or the
        // player's streaming session cookies get dropped. setAcceptCookie is global (once).
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        container.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        webView.visibility = View.GONE
    }

    override fun load(html: String, baseUrl: String) {
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
    }

    override fun setVisible(visible: Boolean) {
        webView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) webView.bringToFront()
    }

    override fun evaluate(js: String) {
        webView.evaluateJavascript(js, null)
    }

    override fun release() {
        container.removeView(webView)
        webView.destroy()
    }

    /** The `window.ymixChannelAndroid` object the page agent posts to; re-posts onto the main looper. */
    private class JsInterface(
        private val mainHandler: Handler,
        private val sink: WebViewMessageSink,
    ) {
        @JavascriptInterface
        fun postMessage(channel: String, payload: String, isMainFrame: Boolean, origin: String?) {
            mainHandler.post { sink.onMessage(channel, payload) }
        }

        @JavascriptInterface
        fun remoteDebug(scope: String, payload: String) {
            // Diagnostics channel — not part of the MediaEngine surface; ignored for now.
        }
    }

    private class WebViewClientImpl : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (url.matches(Regex(".*\\.js([?#].*)*$")) && !url.contains("_doNotBlock=42")) {
                return WebResourceResponse(
                    "text/plain",
                    null,
                    404,
                    "Not found",
                    emptyMap(),
                    ByteArrayInputStream(
                        byteArrayOf(),
                    ),
                )
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    private class WebChromeClientImpl : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            println("[${consoleMessage.messageLevel()}] ${consoleMessage.message()}\nSource: ${consoleMessage.sourceId()} (${consoleMessage.lineNumber()})")
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap? {
            val dummyPoster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            dummyPoster.eraseColor(Color.BLACK)
            return dummyPoster
        }
    }
}
