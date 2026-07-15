package ymusic.player.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import ymusic.player.WebViewPlayerContext

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
    }.apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(JsInterface(mainHandler, sink), "ymixChannelAndroid")
    }

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
    }

    override fun load(html: String, baseUrl: String) {
        webView.bringToFront() // this engine is becoming active — show its surface over a co-hosted one
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
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
}
