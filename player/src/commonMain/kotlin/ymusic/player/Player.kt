package ymusic.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * The playback facade the app talks to — one player, many items. Owns the queue
 * (shuffle/loop/reorder), routes each item to the engine that plays it ([LocalPlayer] for
 * [PlayableItem.LocalUri], [WebViewPlayer] for [PlayableItem.YtVideoId]), auto-advances at end of
 * track, and re-exposes the **active** engine's flows so observers never care which engine is
 * playing (a switch-latest merge — when the engine changes, the facade flows follow the new one).
 *
 * Main-thread only, like the engines it drives ([MediaEngine]); flows collect from anywhere.
 * [config] is facade state: it survives track and engine changes, applied atomically at every load and
 * pushed to the active engine on every change via [MediaEngine.setConfig].
 */
interface Player {
    /** The active engine's [MediaEngine.status]; [PlaybackStatus] default when nothing has loaded. */
    val status: StateFlow<PlaybackStatus>

    /** The active engine's [MediaEngine.progress] (~5 Hz play-head). */
    val progress: StateFlow<Progress>

    val queue: StateFlow<QueueState>

    /** Capability states of the active engine ([VideoQualitySelectable] etc.); DEFAULT when absent. */
    val videoQuality: StateFlow<VideoQualityState>
    val captions: StateFlow<CaptionState>
    val audioTracks: StateFlow<AudioTrackState>

    /** The current playback settings, pushed to the active engine on every change. Audio-only is
     *  [PlaybackConfig.audioOnly] (`preferVideoQuality == "zero"`), not a separate flag. */
    val config: StateFlow<PlaybackConfig>

    /**
     * The engine currently bound to the queue's item — the render seam: a `PlayerSurface` watches
     * this to host the right platform view (video surface vs WebView). Null before the first load.
     */
    val currentEngine: StateFlow<MediaEngine<*>?>

    /** Remaining sleep-timer time (ticked ~1 Hz), or null when no timer is armed. */
    val sleepTimerMs: StateFlow<Long?>

    /** Replace the queue and load [playIndex]. An empty [items] clears and stops. */
    fun setQueue(
        items: List<PlayableItem>,
        playIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    )

    /** Append (or insert at [at]) without interrupting playback. */
    fun add(items: List<PlayableItem>, at: Int? = null)

    /** Insert right after the current item. */
    fun addToPlayNext(items: List<PlayableItem>)

    fun move(from: Int, to: Int)

    fun removeAt(index: Int)

    fun clearQueue()

    /** Jump to and load the queue item at [index]. */
    fun playAt(index: Int)

    fun play()

    fun pause()

    fun togglePlay()

    /** Stop and unload; the queue is kept. */
    fun stop()

    fun seekTo(positionMs: Long)

    fun skipNext()

    /** Goes to the previous item, or restarts the current one when it has played more than ~3 s. */
    fun skipPrev()

    fun fastForward(ms: Long = 10_000L)

    fun rewind(ms: Long = 10_000L)

    fun setLoopMode(mode: LoopMode)

    fun setShuffle(enabled: Boolean)

    fun setVolume(volume: Float)

    fun setSpeed(speed: Float)

    /** Audio-only sets [PlaybackConfig.preferVideoQuality] to [VideoQualityState.AUDIO_ONLY] / [AUTO][VideoQualityState.AUTO]. */
    fun setAudioOnly(audioOnly: Boolean)

    /** Sets [PlaybackConfig.preferVideoQuality] (a [VideoQualityOption.quality], `AUTO`, or `AUDIO_ONLY`). */
    fun selectVideoQuality(quality: String)

    /** Sets [PlaybackConfig.preferCaptionLanguage] (a language code, or null for undetermined). */
    fun selectCaption(languageCode: String?)

    /** Sets [PlaybackConfig.captionVisible]. */
    fun setCaptionVisible(visible: Boolean)

    /** Sets [PlaybackConfig.scaleMode] (how the video fills its surface). */
    fun setScaleMode(mode: ScaleMode)

    /** The one immediate action — forwarded to the active engine when it is [AudioTrackSelectable]. */
    fun selectAudioTrack(id: String)

    /** Pause automatically after [durationMs]; null disarms. */
    fun setSleepTimer(durationMs: Long?)

    /** Releases both engines and stops all internal work. The instance is unusable afterwards. */
    fun release()
}

/**
 * Lazy engine factories — an engine is only constructed when the queue first needs its item type
 * (the WebView engine in particular is expensive: a whole WebView + injected bundle). [webView] may
 * be null on hosts without a WebView stack; a [PlayableItem.YtVideoId] then fails with `E60050`
 * through [Player.status] instead of crashing.
 */
class PlayerEngines(
    val local: () -> LocalPlayer,
    val webView: (() -> WebViewPlayer)? = null,
)

fun createPlayer(
    engines: PlayerEngines,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    random: Random = Random.Default,
): Player = PlayerImpl(engines, mainDispatcher, random)
