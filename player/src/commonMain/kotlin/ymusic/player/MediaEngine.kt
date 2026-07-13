package ymusic.player

import kotlinx.coroutines.flow.StateFlow

/**
 * A resolved, playable source. The `Player` router picks an engine by variant; each engine plays
 * only the variant it understands (a [LocalPlayer] only ever receives a [LocalUri]).
 */
sealed interface PlayableItem {
    /** A local file — a `file://` URI or an absolute path. Played by the native-local engine. */
    data class LocalUri(val uri: String) : PlayableItem

    /** A YouTube video id. Played by the WebView engine (`MediaEngine<YtVideoId>`). */
    data class YtVideoId(val videoId: String) : PlayableItem
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
interface MediaEngine<in S : PlayableItem> {
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
     * replaces whatever was loaded before. [S] is the engine's source variant — a mismatched source
     * is a compile error, not a runtime failure.
     *
     * [config] is applied **atomically before the first frame**, so the media starts with the right
     * settings — no full-volume blip or a video flash before audio-only. [setConfig] changes the same
     * settings at runtime through the same application path.
     */
    fun load(
        source: S,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        config: PlaybackConfig = PlaybackConfig(),
    )

    fun play()

    fun pause()

    /** Clamped to `0..duration`. Safe before/after the end. */
    fun seekTo(positionMs: Long)

    /** Stop and unload the current source; returns to [PlaybackState.Idle]. The engine stays usable. */
    fun stop()

    /**
     * Push the full playback settings ([PlaybackConfig]) — the single runtime-tunable path, the same
     * object [load] applies atomically. The engine diffs against its current config and applies only
     * what changed (volume, speed/pitch, video quality incl. audio-only via `preferVideoQuality ==
     * "zero"`, captions, loop, scale). Fields a backend does not support are ignored. Cheap to call on
     * every change; idempotent for an unchanged config.
     */
    fun setConfig(config: PlaybackConfig)

    /** Releases native resources. The instance is unusable afterwards. Idempotent. */
    fun release()
}
