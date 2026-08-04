package ymusic.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ymusic.download.SabrSpecBuilder
import ymusic.download.SabrSpecResolver
import ymusic.player.WebViewPlayer
import ymusic.ytmedia.sabr.SabrStreamSpec

/**
 * Obtains SABR inputs for a download by asking a [WebViewPlayer] to **preload** the video's `/player`
 * response and mint a PO token — no playback. `requestPlayerResponse` preloads the response itself (it
 * fetches `/player` without loading the video on screen), and `requestPoToken` mints against the page's
 * BotGuard (page-level, not per-play), so nothing the user is listening to is disturbed. This is the one
 * piece of the download stack that isn't headless — the attestation chain lives in the page.
 *
 * A [Mutex] serializes access: the page handles one preload/response request cleanly at a time.
 */
class WebViewSabrSpecResolver(
    private val player: WebViewPlayer,
) : SabrSpecResolver {

    private val mutex = Mutex()

    override suspend fun resolve(videoId: String): SabrStreamSpec = mutex.withLock {
        // Ensure the page session is (being) built; warmUp is a no-op once it's up. Main thread — it
        // touches the WebView. requestPlayerResponse/requestPoToken hop to Main internally and queue
        // until the page connects, so a just-warmed engine resolves as soon as it's ready.
        withContext(Dispatchers.Main) { player.warmUp() }
        val playerResponse = player.requestPlayerResponse(videoId)
        val poToken = player.requestPoToken(videoId)
        SabrSpecBuilder.build(videoId, playerResponse, poToken)
    }
}
