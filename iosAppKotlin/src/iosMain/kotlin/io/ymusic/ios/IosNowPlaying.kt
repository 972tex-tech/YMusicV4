package io.ymusic.ios

import io.ymusic.imageloader.ImageLoader
import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlin.math.abs

/**
 * iOS counterpart of Android MediaSession / desktop [io.ymusic.desktop.media.connectMediaSession]:
 * lock screen, Control Center, headset buttons. Does not touch AVAudioSession — that stays
 * `.playback` so background audio keeps running.
 */
@OptIn(ExperimentalForeignApi::class)
fun connectIosMediaSession(
    player: Player,
    scope: CoroutineScope,
    logger: Logger? = null,
    imageLoader: ImageLoader? = null,
) {
    val commands = MPRemoteCommandCenter.sharedCommandCenter()
    commands.playCommand.setEnabled(true)
    commands.pauseCommand.setEnabled(true)
    commands.togglePlayPauseCommand.setEnabled(true)
    commands.nextTrackCommand.setEnabled(true)
    commands.previousTrackCommand.setEnabled(true)
    commands.changePlaybackPositionCommand.setEnabled(true)
    commands.playCommand.addTargetWithHandler { _ ->
        scope.launch { player.play() }
        MPRemoteCommandHandlerStatusSuccess
    }
    commands.pauseCommand.addTargetWithHandler { _ ->
        scope.launch { player.pause() }
        MPRemoteCommandHandlerStatusSuccess
    }
    commands.togglePlayPauseCommand.addTargetWithHandler { _ ->
        scope.launch { player.togglePlay() }
        MPRemoteCommandHandlerStatusSuccess
    }
    commands.nextTrackCommand.addTargetWithHandler { _ ->
        scope.launch { player.skipNext() }
        MPRemoteCommandHandlerStatusSuccess
    }
    commands.previousTrackCommand.addTargetWithHandler { _ ->
        scope.launch { player.skipPrev() }
        MPRemoteCommandHandlerStatusSuccess
    }
    commands.changePlaybackPositionCommand.addTargetWithHandler { event ->
        val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: 0.0
        scope.launch { player.seekTo((seconds * 1000.0).toLong()) }
        MPRemoteCommandHandlerStatusSuccess
    }
    logger?.log(Logger.Level.INFO, "MPRemoteCommandCenter hooked")

    scope.launch {
        var last: NowPlayingSnap? = null
        var lastAtMs = 0L
        var artUrl: String? = null
        var art: MPMediaItemArtwork? = null
        combine(player.status, player.queue, player.progress, player.config) { status, queue, progress, config ->
            NowPlayingSnap(
                title = queue.currentItem?.displayTitle,
                artist = queue.currentItem?.displaySubtitle,
                artworkUrl = queue.currentItem?.displayThumbnailUrl,
                playing = status.state.isPlaying(),
                positionMs = progress.positionMs,
                durationMs = progress.durationMs.takeIf { it > 0 },
                speed = config.speed,
                hasItem = queue.currentItem != null,
            )
        }.collect { state ->
            val now = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
            val prev = last
            val expectedMs = when {
                prev == null -> null
                prev.playing -> prev.positionMs + ((now - lastAtMs) * prev.speed.toDouble()).toLong()
                else -> prev.positionMs
            }
            val urlChanged = state.artworkUrl != artUrl
            if (urlChanged) {
                artUrl = state.artworkUrl
                art = null
            }
            val identityChanged = prev == null || prev.copy(positionMs = 0) != state.copy(positionMs = 0)
            val jumped = expectedMs != null && abs(state.positionMs - expectedMs) > 2_000L
            if (identityChanged || jumped || urlChanged) {
                publish(state, art)
                last = state
                lastAtMs = now
            }
            val url = state.artworkUrl
            if (urlChanged && url != null && imageLoader != null) {
                launch {
                    val loaded = loadArtwork(imageLoader, url, logger)
                    if (artUrl == url && loaded != null) {
                        art = loaded
                        publish(state, loaded)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadArtwork(loader: ImageLoader, url: String, logger: Logger?): MPMediaItemArtwork? {
    return try {
        val path = loader.loadFile(url, ARTWORK_MAX_PX, ARTWORK_MAX_PX)
            ?: return null
        val image = UIImage.imageWithContentsOfFile(path.toString()) ?: return null
        val size = image.size
        MPMediaItemArtwork(boundsSize = size) { _ -> image }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger?.debug { "now-playing artwork failed for $url: $e" }
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun publish(state: NowPlayingSnap, artwork: MPMediaItemArtwork?) {
    val center = MPNowPlayingInfoCenter.defaultCenter()
    if (!state.hasItem) {
        center.nowPlayingInfo = null
        center.playbackState = MPNowPlayingPlaybackStateStopped
        return
    }
    val info = mutableMapOf<Any?, Any?>()
    state.title?.let { info[MPMediaItemPropertyTitle] = it }
    state.artist?.let { info[MPMediaItemPropertyArtist] = it }
    artwork?.let { info[MPMediaItemPropertyArtwork] = it }
    state.durationMs?.let { info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = it / 1000.0) }
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = NSNumber(double = state.positionMs / 1000.0)
    info[MPNowPlayingInfoPropertyPlaybackRate] =
        NSNumber(double = if (state.playing) state.speed.toDouble() else 0.0)
    center.nowPlayingInfo = info
    center.playbackState =
        if (state.playing) MPNowPlayingPlaybackStatePlaying else MPNowPlayingPlaybackStatePaused
}

private const val ARTWORK_MAX_PX = 640

private data class NowPlayingSnap(
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val speed: Float,
    val hasItem: Boolean,
)
