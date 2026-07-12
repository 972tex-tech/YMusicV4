package ymusic.player

import kmpcommon.error.CodedError

/** Coarse lifecycle of the single loaded file. Transitions are rare (a state flow, not a ticker). */
enum class PlaybackState {
    /** Nothing loaded, or [LocalPlayer.stop] / released. */
    Idle,

    /** Loaded and prefetching; not yet renderable. */
    Buffering,

    /** Renderable — playing when [PlaybackStatus.playWhenReady], otherwise paused. */
    Ready,

    /** Reached the end of the file. */
    Ended,

    /** Playback failed; [PlaybackStatus.error] carries the code. */
    Error,
}

/**
 * A recoverable classification the caller can surface — a [CodedError] with a stable `E6xxxx` player
 * code. [message] is developer detail (the backend's own error text) — log material, never shown.
 */
data class PlayerError(
    override val code: String,
    val message: String? = null,
    override val userMessage: String? = null,
) : CodedError

/** Natural pixel dimensions of the current video track. */
data class VideoSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 0f
}

/**
 * The fast-changing play-head — position/duration/buffered — ticked ~5 Hz while playing. Split out
 * from [PlaybackStatus] so an observer of a rarely-changing field (state, videoSize, config) is
 * never woken by a position tick, and needs no `map`/`distinctUntilChanged` filtering.
 */
data class Progress(
    val positionMs: Long = 0L,
    /** [DURATION_UNKNOWN] until the media reports it (or for unbounded streams). */
    val durationMs: Long = DURATION_UNKNOWN,
    val bufferedPositionMs: Long = 0L,
) {
    companion object {
        const val DURATION_UNKNOWN: Long = -1L
    }
}

/**
 * Everything about the player that is NOT the moving play-head — it changes only on discrete events
 * (load, play/pause, config, track/size discovery, error), so its flow stays quiet between them.
 * The play-head lives in [Progress].
 */
data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.Idle,
    val playWhenReady: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    /** When true the video renderer is off (audio-only mode — the music default). */
    val audioOnly: Boolean = false,
    /** Dimensions of the current video track, or null for audio-only / an audio-only file. */
    val videoSize: VideoSize? = null,
    val error: PlayerError? = null,
)
