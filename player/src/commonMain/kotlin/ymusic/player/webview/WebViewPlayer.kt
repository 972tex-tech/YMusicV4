package ymusic.player

import kmphttp.KmpHttpClient
import ymusic.player.webview.WebViewPlayerImpl
import ymusic.player.webview.createWebViewBridge

/**
 * The WebView-hosted YouTube [MediaEngine] — the second engine (alongside [LocalPlayer]), for
 * [ResolvedSource.YtVideoId]. It hosts a platform WebView, injects the `webview-js` bundle at
 * document-start, and drives YouTube's own JS player over the `ymusicAction`/`${sessionId}_*` bridge
 * (see [ymusic.player.webview.WebViewProtocol]). Only the narrow [MediaEngine] surface is wired;
 * YouTube extras (quality/captions/audio-track select) are future capability interfaces.
 *
 * Per the [MediaEngine] contract this is **main-thread only** — WebViews are main-thread-bound on
 * every platform. The caller supplies a **view container**; the player adds its WebView into it
 * (container-injection, like the legacy player), rather than exposing a view to host.
 */
interface WebViewPlayer : MediaEngine

/**
 * Platform handle the player needs: the host view **container** it injects its WebView into, plus any
 * platform context (Android a `Context`). Constructed per platform by the caller.
 */
expect class WebViewPlayerContext

/**
 * Build a [WebViewPlayer]. [engineJs] is the built `webview-js` bundle (the remote-updatable seam —
 * caller supplies it, bundled or fetched). [appVersion] is exposed to the bundle as `mainAppVer`.
 * [httpClient] fetches the YouTube embed HTML that gets the injected bundle (the unified
 * fetch→inject→load path).
 */
fun createWebViewPlayer(
    context: WebViewPlayerContext,
    engineJs: String,
    appVersion: Int,
    httpClient: KmpHttpClient,
): WebViewPlayer = WebViewPlayerImpl(engineJs, appVersion, httpClient) { sink ->
    createWebViewBridge(context, sink)
}
