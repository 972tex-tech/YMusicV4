package io.ymusic.player

import io.ymusic.player.webview.WebViewEvent
import io.ymusic.player.webview.WebViewPlayerImpl
import io.ymusic.player.webview.createWebViewBridge
import kotlinx.coroutines.flow.SharedFlow

/**
 * The WebView-hosted YouTube [MediaEngine] — the second engine (alongside [LocalPlayer]), for
 * [PlayableItem.YtMedia]. It hosts a platform WebView, injects the `webview-js` bundle at
 * document-start, and drives YouTube's own JS player over the `ymusicAction`/`${sessionId}_*` bridge
 * (see [io.ymusic.player.webview.WebViewProtocol]). Beyond the narrow [MediaEngine] core it implements
 * the YouTube capability interfaces ([VideoQualitySelectable], [CaptionSelectable],
 * [AudioTrackSelectable]) — a generic `MediaEngine<*>` caller reaches them via `engine as? …`.
 *
 * Per the [MediaEngine] contract this is **main-thread only** — WebViews are main-thread-bound on
 * every platform. The caller supplies a **view container**; the player adds its WebView into it
 * (container-injection, like the legacy player), rather than exposing a view to host.
 */
interface WebViewPlayer :
    MediaEngine<PlayableItem.YtMedia>,
    VideoQualitySelectable,
    CaptionSelectable,
    AudioTrackSelectable {
    /** Watch-next payloads the page reports for the music videos it plays — the Recommended feed's
     *  raw material, forwarded to the scraping engine by the app (see [Player.watchNextSeen]). */
    val watchNextSeen: SharedFlow<WebViewEvent.WatchNextSeen>

    /** Mark the page stale so the NEXT [load] re-fetches `/embed` — used after the cookie identity
     *  changes (YouTube sign-in / sign-out) so the running page picks up the new identity. The
     *  current song plays on undisturbed until it ends. */
    fun refreshOnNext()

    /** Eagerly build the page session (fetch + inject `/embed`) with no video queued, so a later [load]
     *  or download resolves against an already-connected page instead of paying the init cost then.
     *  No-op once the page is initializing/initialized. Main thread. */
    fun warmUp()

    /**
     * The decoded player response for [videoId] as the page's opaque `data` JSON (streamingData with
     * sig/n-decoded `serverAbrStreamingUrl` + adaptiveFormats, `videoPlaybackUstreamerConfig`, client
     * info). The page **preloads** the response (fetches `/player` without playing the video), so this
     * works for any video and doesn't disturb what's currently playing; a cached / currently-playing
     * response returns immediately. Feeds the SABR downloader / player cache (`:yt-media`). The page must
     * be connected (warm it first); throws on timeout.
     */
    suspend fun requestPlayerResponse(videoId: String): String

    /** Mint a GVS PO token (base64) for [videoId] via the page's BotGuard. Throws on timeout/failure. */
    suspend fun requestPoToken(videoId: String): String
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
    logger: kmpcommon.log.Logger? = null,
): WebViewPlayer = WebViewPlayerImpl(engineJs, appVersion, embedHtml, logger) { sink ->
    createWebViewBridge(context, sink, logger)
}
