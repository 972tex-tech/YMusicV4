package ymusic.player

import kotlinx.coroutines.flow.SharedFlow
import ymusic.player.webview.WebViewEvent
import ymusic.player.webview.WebViewPlayerImpl
import ymusic.player.webview.createWebViewBridge

/**
 * The WebView-hosted YouTube [MediaEngine] — the second engine (alongside [LocalPlayer]), for
 * [PlayableItem.YtVideoId]. It hosts a platform WebView, injects the `webview-js` bundle at
 * document-start, and drives YouTube's own JS player over the `ymusicAction`/`${sessionId}_*` bridge
 * (see [ymusic.player.webview.WebViewProtocol]). Beyond the narrow [MediaEngine] core it implements
 * the YouTube capability interfaces ([VideoQualitySelectable], [CaptionSelectable],
 * [AudioTrackSelectable]) — a generic `MediaEngine<*>` caller reaches them via `engine as? …`.
 *
 * Per the [MediaEngine] contract this is **main-thread only** — WebViews are main-thread-bound on
 * every platform. The caller supplies a **view container**; the player adds its WebView into it
 * (container-injection, like the legacy player), rather than exposing a view to host.
 */
interface WebViewPlayer :
    MediaEngine<PlayableItem.YtVideoId>,
    VideoQualitySelectable,
    CaptionSelectable,
    AudioTrackSelectable {
    /** Watch-next payloads the page reports for the music videos it plays — the Recommended feed's
     *  raw material, forwarded to the scraping engine by the app (see [Player.watchNextSeen]). */
    val watchNextSeen: SharedFlow<WebViewEvent.WatchNextSeen>
}

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
): WebViewPlayer = WebViewPlayerImpl(engineJs, appVersion, embedHtml) { sink ->
    createWebViewBridge(context, sink)
}
