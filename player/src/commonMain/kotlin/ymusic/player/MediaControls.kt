package ymusic.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The OS now-playing/transport seam ([docs/player/system-integration.md]): the app publishes what
 * is playing, the OS sends transport commands back. Platform sinks implement this (Media3
 * `MediaSession` / `MPNowPlayingInfoCenter` / SMTC / MPRIS); [bindMediaControls] does all the
 * derivation from the [Player] flows, so a sink only maps [NowPlayingInfo] onto its OS API and
 * forwards hardware events as [TransportCommand]s.
 */
interface MediaControls {
    /** Publish [info] to the OS surface; null clears it (nothing playing). */
    fun update(info: NowPlayingInfo?)

    fun release()
}

data class NowPlayingInfo(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val artworkUrl: String?,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

sealed interface TransportCommand {
    data object Play : TransportCommand

    data object Pause : TransportCommand

    data object TogglePlay : TransportCommand

    data object Next : TransportCommand

    data object Previous : TransportCommand

    data object Stop : TransportCommand

    data class SeekTo(val positionMs: Long) : TransportCommand
}

fun Player.handle(command: TransportCommand) {
    when (command) {
        TransportCommand.Play -> play()
        TransportCommand.Pause -> pause()
        TransportCommand.TogglePlay -> togglePlay()
        TransportCommand.Next -> skipNext()
        TransportCommand.Previous -> skipPrev()
        TransportCommand.Stop -> stop()
        is TransportCommand.SeekTo -> seekTo(command.positionMs)
    }
}

/**
 * Feed [controls] from [player]'s flows until the returned [Job] is cancelled. [describe] supplies
 * the display title/artist/artwork for a queue item (the queue holds only [PlayableItem]s — the
 * UI layer owns the metadata). The caller routes the sink's inbound commands via [Player.handle].
 */
fun bindMediaControls(
    player: Player,
    controls: MediaControls,
    scope: CoroutineScope,
    describe: (PlayableItem) -> NowPlayingDescription,
): Job = scope.launch {
    combine(player.status, player.progress, player.queue) { status, progress, queue ->
        val item = queue.currentItem ?: return@combine null
        if (status.state == PlaybackState.Idle) return@combine null
        val meta = describe(item)
        NowPlayingInfo(
            title = meta.title,
            artist = meta.artist,
            durationMs = progress.durationMs,
            positionMs = progress.positionMs,
            isPlaying = status.playWhenReady && status.state == PlaybackState.Ready,
            artworkUrl = meta.artworkUrl,
            hasNext = queue.currentIndex < queue.items.lastIndex || queue.loopMode == LoopMode.All,
            hasPrevious = queue.currentIndex > 0 || queue.loopMode == LoopMode.All,
        )
    }.collect { controls.update(it) }
}

data class NowPlayingDescription(
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
)
