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

    /**
     * JS the engine evaluates (at document-start, ahead of the globals + bundle) to install the
     * page → host transport the bundle's `postMsg` expects. Empty on platforms that provide it
     * natively (Android's `ymixChannelAndroid` interface, Apple's `webkit.messageHandlers`); the JVM
     * bridge uses it to shim `window.ymixChannelAndroid` onto JCEF's `cefQuery`.
     */
    val bootPrelude: String get() = ""

    /**
     * Load [html] as the document at [baseUrl] (origin) — the injected bundle runs at document-start.
     * The bridge brings its WebView to the front of the shared container here (this engine is becoming
     * the active one); the view is otherwise left attached and visible for its whole lifetime — the
     * WebView shows YouTube's own thumbnail in audio-only mode, so audio-only never hides it (mirrors
     * legacy `BasicMediaPlayer`, which has no `setVideoVisible`: each player owns its view).
     */
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
