package ymusic.player.webview

import ymusic.player.WebViewPlayerContext

/**
 * The thin per-platform WebView the shared engine drives. Everything YouTube-specific lives in the
 * common engine + [WebViewProtocol]; a bridge only: hosts a WebView in the caller's container, loads
 * HTML with a base URL, evaluates JS, and forwards page → host channel messages to [sink]. All
 * methods are called on the main thread (the [ymusic.player.MediaEngine] contract).
 */
internal interface WebViewBridge {
    /** `"android"` | `"darwin"` | `"jvm"` — installed as `ymixInitConfig.platform` for the bundle. */
    val platform: String

    /** Load [html] as the document at [baseUrl] (origin) — the injected bundle runs at document-start. */
    fun load(html: String, baseUrl: String)

    /** Evaluate [js] in the page (fire-and-forget). */
    fun evaluate(js: String)

    /** Tear down the WebView and detach it from the container. */
    fun release()
}

/** Sink for page → host channel posts (`channel`, JSON `payload`). Bridges deliver it on the main thread. */
internal fun interface WebViewMessageSink {
    fun onMessage(channel: String, payload: String)
}

internal expect fun createWebViewBridge(
    context: WebViewPlayerContext,
    sink: WebViewMessageSink,
): WebViewBridge
