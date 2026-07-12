package ymusic.player

import kotlinx.coroutines.flow.StateFlow

/**
 * A resolved, playable source. The `Player` router picks an engine by variant; each engine plays
 * only the variant it understands (a [LocalPlayer] only ever receives a [LocalUri]).
 */
sealed interface ResolvedSource {
    /** A local file — a `file://` URI or an absolute path. Played by the native-local engine. */
    data class LocalUri(val uri: String) : ResolvedSource

    /** A YouTube video id. Played by the (future) WebView engine; not handled by [LocalPlayer]. */
    data class YtVideoId(val videoId: String) : ResolvedSource
}

/**
 * The engine SPI that every playback backend satisfies — today the native-local engine
 * ([LocalPlayer]); later the WebView/YouTube engine. The `Player` facade/router drives whichever
 * engine plays the current item through this one contract and merges [status]/[progress] the same
 * way regardless of which engine is active (swapping engines when the play-index crosses item types).
 *
 * This is a deliberately **narrow common core** — only what genuinely overlaps between a native
 * decoder and a WebView player. Engine-specific features do NOT go here; they belong behind optional
 * capability interfaces queried with `engine as? Capability` (planned: audio-track selection,
 * captions, video-quality selection for YouTube, an equalizer/`audioSessionId` handle for local) —
 * never as degenerate methods every engine must stub, which was the legacy `BasicMediaPlayer` smell.
 *
 * **Main-thread only.** Create the engine and call every method on the **main thread**. Each
 * backend's underlying native player (ExoPlayer / AVPlayer / libVLC / a WebView) does its own
 * decoding/playback on its own threads; only this control surface is main-thread-bound, and no call
 * is marshaled — that is the caller's responsibility. The [status] / [progress] flows may be
 * collected from any thread.
 */
interface MediaEngine {
    /**
     * Rarely-changing status: playback state, play/pause intent, volume/speed, audio-only, video
     * size, error. Emits only on discrete events — never on a position tick (that's [progress]).
     */
    val status: StateFlow<PlaybackStatus>

    /** The play-head — position/duration/buffered — ticked ~5 Hz while playing. */
    val progress: StateFlow<Progress>

    /**
     * Start loading [source] and return immediately. Readiness is observed through [status]: the
     * state goes [PlaybackState.Buffering] → [PlaybackState.Ready], or [PlaybackState.Error] (with
     * [PlaybackStatus.error]) on failure — loading never throws. [startPositionMs] seeks before the
     * first frame; playback begins as soon as it is ready when [playWhenReady]. A second [load]
     * replaces whatever was loaded before. An engine that does not handle [source]'s variant reports
     * [PlaybackState.Error].
     */
    fun load(source: ResolvedSource, startPositionMs: Long = 0L, playWhenReady: Boolean = true)

    fun play()

    fun pause()

    /** Clamped to `0..duration`. Safe before/after the end. */
    fun seekTo(positionMs: Long)

    /** Stop and unload the current source; returns to [PlaybackState.Idle]. The engine stays usable. */
    fun stop()

    /** Linear gain, clamped to `0f..1f`. */
    fun setVolume(volume: Float)

    /** Playback rate (`1f` = normal). Pitch handling is per-backend; see the module docs. */
    fun setSpeed(speed: Float)

    /**
     * Toggle audio-only mode. When true the video renderer is disabled (the music-app default) — the
     * source's audio still plays, but no frames are decoded/shown even if a `PlayerSurface` is
     * attached. When false, video is rendered wherever a surface is bound. A no-op for audio-only sources.
     */
    fun setAudioOnly(audioOnly: Boolean)

    /** Releases native resources. The instance is unusable afterwards. Idempotent. */
    fun release()
}
