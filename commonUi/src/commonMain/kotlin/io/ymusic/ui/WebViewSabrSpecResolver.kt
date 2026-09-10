package io.ymusic.ui

import io.ymusic.download.SabrSpecBuilder
import io.ymusic.download.SabrSpecResolver
import io.ymusic.player.YtEngine
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Obtains SABR inputs for a download from a [YtEngine] (`MsePlayer` or WebView).
 * `requestPlayerResponse` returns cached `/player` + a decoded SABR URL the player already
 * fetched, or preloads the video so those requests fire. `requestPoToken` mints a content PO.
 */
class WebViewSabrSpecResolver(
    private val player: YtEngine,
) : SabrSpecResolver {

    private val mutex = Mutex()

    override suspend fun resolve(videoId: String): SabrStreamSpec = mutex.withLock {
        withContext(Dispatchers.Main) { player.warmUp() }
        val playerResponse = player.requestPlayerResponse(videoId)
        val poToken = player.requestPoToken(videoId)
        SabrSpecBuilder.build(videoId, playerResponse, poToken)
    }
}
