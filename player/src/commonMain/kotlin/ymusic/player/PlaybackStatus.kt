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

/** How the video fills its surface. Mirrors the legacy player's scale modes. */
enum class ScaleMode { COVER, FILL, CONTAIN }

/**
 * The full settable playback state — a single object pushed to the engine via [MediaEngine.setConfig]
 * (and carried by [MediaEngine.load] to apply atomically before the first frame). One config, one
 * application path: the engine diffs old→new and applies what changed, so load-time and runtime
 * settings never drift. A faithful port of the legacy `BasicMediaPlayer.Config`.
 *
 * **Audio-only is not a boolean here** — it is [preferVideoQuality]`.first == `[VideoQualityState.AUDIO_ONLY]
 * (`"zero"`), the music-app default. The `Pair<String, Long>` preference fields carry a monotonic
 * sequence in `.second` so the engine can tell the freshest user choice from a stale restore.
 *
 * Quality/caption *selection* lives here (as in legacy); the available-options **state** stays on the
 * read-only capability flows ([VideoQualitySelectable.videoQuality] etc.). Audio-track selection is an
 * immediate action ([AudioTrackSelectable.selectAudioTrack]), not a persistent preference.
 */
data class PlaybackConfig(
    val preferVideoQuality: Pair<String, Long> = VideoQualityState.AUDIO_ONLY to 0L,
    val captionVisible: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val pitchFactor: Float = 1f,
    val loop: Boolean = false,
    val preferCaptionLanguage: Pair<String, Long> = UNDETERMINED_LANGUAGE to 0L,
    val scaleMode: ScaleMode = ScaleMode.CONTAIN,
) {
    init {
        require(volume in 0f..1f) { "volume out of range: $volume" }
    }

    /** True when video is disabled (the music default) — `preferVideoQuality` is [VideoQualityState.AUDIO_ONLY]. */
    val audioOnly: Boolean get() = preferVideoQuality.first == VideoQualityState.AUDIO_ONLY

    companion object {
        const val UNDETERMINED_LANGUAGE = "und"
    }
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
    /** Dimensions of the current video track, or null for audio-only / an audio-only file. */
    val videoSize: VideoSize? = null,
    val error: PlayerError? = null,
)
