package io.ymusic.player

import kmpcommon.error.CodedError

/**
 * Engine + media lifecycle, preserving the legacy player contract. [readyToLoad] means an ordinary
 * media load can be sent to the existing initialized engine; it does not mean that media is ready
 * for playback.
 */
sealed class PlaybackState(val readyToLoad: Boolean) {
    /** The engine does not yet have a usable native/page session. */
    data class Uninitialized(val isInitializing: Boolean) : PlaybackState(readyToLoad = false)

    /** A failed load/session. A broken session must be reset before another load. */
    data class Error(val error: PlayerError) :
        PlaybackState(readyToLoad = error.canRetry && !error.needReset)

    /** The initialized engine has no media loaded. */
    data object Idle : PlaybackState(readyToLoad = true)

    /** Media is loaded; buffering/ended remain orthogonal to the user's play intent. */
    data class Prepared(
        val playWhenReady: Boolean,
        val buffering: Boolean,
        val ended: Boolean,
    ) : PlaybackState(readyToLoad = true)
}

fun PlaybackState.isPlaying(): Boolean = (this as? PlaybackState.Prepared)?.playWhenReady == true

fun PlaybackState.isEnded(): Boolean = (this as? PlaybackState.Prepared)?.ended == true

/** How the video fills its surface. Mirrors the legacy player's scale modes. */
enum class ScaleMode { COVER, FILL, CONTAIN }

/**
 * The full settable playback state — a single object pushed to the engine via [MediaEngine.setConfig]
 * (and carried by [MediaEngine.load] to apply atomically before the first frame). One config, one
 * application path: the engine diffs old→new and applies what changed, so load-time and runtime
 * settings never drift. A faithful port of the legacy `BasicMediaPlayer.Config`.
 *
 * **Audio-only is [audioOnly]**, independent of [preferVideoQuality]. Quality is the last Auto/hd720/…
 * choice and is restored when audio-only turns off. The `Pair<String, Long>` preference fields carry a
 * monotonic sequence in `.second` so the engine can tell the freshest user choice from a stale restore.
 *
 * Quality/caption *selection* lives here (as in legacy); the available-options **state** stays on the
 * read-only capability flows ([VideoQualitySelectable.videoQuality] etc.). Audio-track selection is an
 * immediate action ([AudioTrackSelectable.selectAudioTrack]), not a persistent preference.
 */
data class PlaybackConfig(
    val preferVideoQuality: Pair<String, Long> = VideoQualityState.AUTO to 0L,
    val audioOnly: Boolean = true,
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

    companion object {
        const val UNDETERMINED_LANGUAGE = "und"
    }
}

/**
 * A recoverable classification the caller can surface — a [CodedError] with a stable `E6xxxx` player
 * code. [message] is developer detail (the backend's own error text) — log material, never shown.
 *
 * [canRetry]/[needReset] mirror the legacy `PlayerState.Error` flags: [canRetry] = a fresh
 * [MediaEngine.load] of the same source may succeed (false = don't offer retry — e.g. no engine for
 * the item type); [needReset] = the engine instance itself is broken (dead WebView page, auth wall),
 * not just this load. Every engine's [MediaEngine.load] tears down and rebuilds its session, so a
 * retry is always expressed as a re-load — [needReset] is a severity signal, not a separate call.
 */
data class PlayerError(
    override val code: String,
    val message: String? = null,
    override val userMessage: String? = null,
    val canRetry: Boolean = true,
    val needReset: Boolean = false,
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
    /** Playhead on the scrubber, 0..[durationMs]. Live DVR is already mapped into that range. */
    val positionMs: Long = 0L,
    /** Scrubber length. [DURATION_UNKNOWN] until the media reports it. */
    val durationMs: Long = DURATION_UNKNOWN,
    val bufferedPositionMs: Long = 0L,
    /** False for a live head with no DVR — the host must not seek. */
    val seekable: Boolean = true,
    val isLive: Boolean = false,
    /** Media-time origin so [seekTo] can map a scrubber position back. Not a UI field. */
    val seekableStartMs: Long = 0L,
    val seekableEndMs: Long = 0L,
) {
    /**
     * Left caption, milliseconds. VOD: elapsed. Live: delay behind the head (≤ 0, UI shows a minus).
     */
    val progressLabelMs: Long
        get() = if (isLive) {
            val span = durationMs.coerceAtLeast(0L)
            -(span - positionMs).coerceAtLeast(0L)
        } else {
            positionMs
        }

    /** Right caption, milliseconds. Null when [isLive] — the UI shows LIVE. */
    val durationLabelMs: Long?
        get() = if (isLive) null else durationMs.takeIf { it > 0L }

    /** Within [thresholdMs] of the live head. */
    fun atLiveHead(thresholdMs: Long = 2_000L): Boolean {
        if (!isLive) return false
        val span = durationMs.coerceAtLeast(0L)
        return span - positionMs <= thresholdMs
    }

    fun mediaSeekMs(uiPositionMs: Long): Long {
        val window = isLive && seekableEndMs > seekableStartMs
        return if (window) seekableStartMs + uiPositionMs.coerceAtLeast(0L) else uiPositionMs.coerceAtLeast(0L)
    }

    companion object {
        const val DURATION_UNKNOWN: Long = -1L

        /** Map YouTube/media times onto the scrubber the UI already understands (0..duration). */
        fun of(
            positionMs: Long,
            durationMs: Long,
            bufferedPositionMs: Long = 0L,
            seekable: Boolean = true,
            isLive: Boolean = false,
            seekableStartMs: Long = 0L,
            seekableEndMs: Long = 0L,
        ): Progress {
            val window = isLive && seekableEndMs > seekableStartMs
            val start = if (window) seekableStartMs else 0L
            val end = when {
                window -> seekableEndMs
                durationMs > 0L -> durationMs
                else -> 0L
            }
            val span = (end - start).coerceAtLeast(0L)
            val pos = if (span > 0L) {
                (positionMs - start).coerceIn(0L, span)
            } else {
                (positionMs - start).coerceAtLeast(0L)
            }
            val dur = if (span > 0L) span else if (durationMs > 0L) durationMs else DURATION_UNKNOWN
            return Progress(
                positionMs = pos,
                durationMs = dur,
                bufferedPositionMs = (bufferedPositionMs - start).coerceAtLeast(0L),
                seekable = seekable,
                isLive = isLive,
                seekableStartMs = seekableStartMs,
                seekableEndMs = if (window) seekableEndMs else end,
            )
        }
    }
}

/**
 * Everything about the player that is NOT the moving play-head — it changes only on discrete events
 * (load, play/pause, config, track/size discovery, error), so its flow stays quiet between them.
 * The play-head lives in [Progress].
 */
data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.Uninitialized(isInitializing = false),
    val volume: Float = 1f,
    val speed: Float = 1f,
    /** Dimensions of the current video track, or null for audio-only / an audio-only file. */
    val videoSize: VideoSize? = null,
    /** Unbounded stream (HTML duration Infinity). DVR vs head-only is [Progress.seekable]. */
    val live: Boolean = false,
) {
    val playWhenReady: Boolean get() = state.isPlaying()
    val error: PlayerError? get() = (state as? PlaybackState.Error)?.error
}
