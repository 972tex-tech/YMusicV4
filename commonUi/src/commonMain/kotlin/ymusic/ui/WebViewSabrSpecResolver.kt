package ymusic.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ymusic.download.SabrSpecBuilder
import ymusic.download.SabrSpecResolver
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.PlaybackState
import ymusic.player.WebViewPlayer
import ymusic.ytmedia.sabr.SabrStreamSpec

/**
 * Obtains SABR inputs for a download by driving a [WebViewPlayer]: it loads the video muted + audio-only,
 * waits until playback advances (the signal that the `/player` response is cached and BotGuard has minted
 * a PO token), then pulls both out and builds the [SabrStreamSpec]. This is the one piece of the
 * download stack that isn't headless — the attestation chain lives in the page (see the download probe,
 * which does exactly this inline).
 *
 * A [Mutex] serializes priming: the player loads one video at a time, so concurrent resolves would fight
 * over it. Pair this with a **dedicated** download WebViewPlayer in the shell so priming never disturbs
 * the user's playback; a shared player would interrupt what's playing.
 */
class WebViewSabrSpecResolver(
    private val player: WebViewPlayer,
    private val timeoutMs: Long = 90_000,
    private val pollMs: Long = 500,
) : SabrSpecResolver {

    private val mutex = Mutex()

    override suspend fun resolve(videoId: String): SabrStreamSpec = mutex.withLock {
        // Muted, audio-only (PlaybackConfig's default preferVideoQuality is AUDIO_ONLY); playback
        // advancing past ~1.5s is our "response cached + PO token minted" signal.
        player.load(
            PlayableItem.YtVideoId(videoId),
            startPositionMs = 0L,
            playWhenReady = true,
            config = PlaybackConfig(volume = 0f),
        )
        awaitPlayback(videoId)
        val playerResponse = player.requestPlayerResponse(videoId)
        val poToken = player.requestPoToken(videoId)
        SabrSpecBuilder.build(videoId, playerResponse, poToken)
    }

    private suspend fun awaitPlayback(videoId: String) {
        var waited = 0L
        while (waited < timeoutMs) {
            if (player.progress.value.positionMs > 1_500) return
            val err = (player.status.value.state as? PlaybackState.Error)?.error
            if (err != null && !err.canRetry) error("playback error ${err.code} for $videoId: ${err.message}")
            delay(pollMs)
            waited += pollMs
        }
        error("playback did not advance within ${timeoutMs}ms for $videoId")
    }
}
