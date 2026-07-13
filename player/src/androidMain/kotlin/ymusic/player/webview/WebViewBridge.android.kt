package ymusic.player.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private val webView = WebView(androidContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(JsInterface(mainHandler, sink), "ymixChannelAndroid")
    }

    init {
        container.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun load(html: String, baseUrl: String) {
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", baseUrl)
    }

    override fun evaluate(js: String) {
        webView.evaluateJavascript(js, null)
    }

    override fun setVideoVisible(visible: Boolean) {
        webView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
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
