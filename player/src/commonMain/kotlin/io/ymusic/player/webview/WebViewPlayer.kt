package io.ymusic.player

import io.ymusic.player.webview.WebViewPlayerImpl
import io.ymusic.player.webview.createWebViewBridge

/**
 * The WebView-hosted YouTube [YtEngine] — used to cook SABR download inputs (player response + PO
 * token) off an injected `webview-js` page. Playback is [io.ymusic.player.mse.MsePlayer].
 *
 * Per the [MediaEngine] contract this is **main-thread only** — WebViews are main-thread-bound on
 * every platform. The caller supplies a **view container**; the player adds its WebView into it
 * (container-injection), rather than exposing a view to host.
 */
interface WebViewPlayer : YtEngine

/**
 * Platform handle the player needs: the host view **container** it injects its WebView into, plus any
 * platform context (Android a `Context`). Constructed per platform by the caller.
 */
expect class WebViewPlayerContext

/**
 * Build a [WebViewPlayer]. [engineJs] is the built `webview-js` bundle (the remote-updatable seam —
 * caller supplies it, bundled or fetched). [appVersion] is exposed to the bundle as `mainAppVer`.
 * [embedHtml] serves the YouTube embed page for the given User-Agent (the WebView's own, passed
 * verbatim) with the `INJECT_JS_HERE` placeholder where the player splices its boot script —
 * normally the scraping engine's `yt/embed` command (`DynamicEngineManager.embedPage`), so the
 * fetch shares the engine's cookie identity and the HTML rewrite stays remotely updatable.
 */
fun createWebViewPlayer(
    context: WebViewPlayerContext,
    engineJs: String,
    appVersion: Int,
    embedHtml: suspend (userAgent: String) -> String,
    logger: kmpcommon.log.Logger? = null,
): WebViewPlayer = WebViewPlayerImpl(engineJs, appVersion, embedHtml, logger) { sink ->
    createWebViewBridge(context, sink, logger)
}
