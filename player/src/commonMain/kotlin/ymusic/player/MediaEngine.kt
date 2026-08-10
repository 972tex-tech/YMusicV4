package ymusic.player

import kotlinx.coroutines.flow.StateFlow

/**
 * A resolved, playable source. The `Player` router picks an engine by variant; each engine plays
 * only the variant it understands (a [LocalPlayer] only ever receives a [LocalMedia]). Both variants
 * are self-describing — they carry their own display metadata — like the legacy LocalSong / YtVideo,
 * so the UI never needs a side map to render a queue item.
 */
sealed interface PlayableItem {
    /** How the item shows in Now Playing — each variant describes itself (title/subtitle fallbacks,
     *  "artist · album"), so the UI reads `item.displayTitle` with no adapter or external `when`. */
    val displayTitle: String
    val displaySubtitle: String?
    val displayThumbnailUrl: String?
    val displayDuration: String?

    /** The source-declared duration in ms, when known ahead of load (the store row, a scraped
     *  badge); null = discovered only once the engine loads the item. Lets a session surface show
     *  durations for every queue row, not just the loaded one. */
    val durationMs: Long?

    /**
     * A local file. [uri] (a `file://` URI or an absolute path) is all the native-local engine needs;
     * the rest travels with the item so the UI (Now Playing display, play-count recording) never has
     * to re-derive it. [mediaId] is the media-store row id (null for a file with no library row, e.g. a
     * download); [title]/[artist]/[album]/[thumbnailUrl] are its display metadata.
     */
    data class LocalMedia(
        val uri: String,
        val mediaId: Long? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val thumbnailUrl: String? = null,
        override val durationMs: Long? = null,
    ) : PlayableItem {
        override val displayTitle: String get() = title ?: uri.substringAfterLast('/')
        override val displaySubtitle: String? get() = listOfNotNull(artist, album).joinToString(" · ").ifEmpty { null }
        override val displayThumbnailUrl: String? get() = thumbnailUrl
        override val displayDuration: String? get() = null
    }

    /**
     * A YouTube video, played by the WebView engine (`MediaEngine<YtMedia>`). [videoId] is all the
     * engine needs; [title]/[artist] (channel) / [thumbnailUrl] / [duration] travel with it for the
     * UI. [live] marks a live stream — no fixed duration, not seekable.
     */
    data class YtMedia(
        val videoId: String,
        val title: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
        val duration: String? = null,
        override val durationMs: Long? = null,
        val live: Boolean = false,
    ) : PlayableItem {
        override val displayTitle: String get() = title ?: videoId
        override val displaySubtitle: String? get() = artist
        override val displayThumbnailUrl: String? get() = thumbnailUrl
        override val displayDuration: String? get() = duration
    }
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
     * state becomes [PlaybackState.Prepared] (first buffering, then ready), or [PlaybackState.Error]
     * on failure — loading never throws. [startPositionMs] seeks before the
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
