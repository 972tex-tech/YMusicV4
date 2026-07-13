package ymusic.player

import kmpcommon.error.TechnicalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

/**
 * libVLC backend via VLCJ, for JVM desktop. Per the `LocalPlayer` contract the caller drives it on
 * the main (UI) thread; libVLC/VLCJ is itself thread-safe, so the position ticker runs on a
 * background dispatcher. Uses a **callback** media-player component so video renders into a
 * lightweight Swing component that a Compose `SwingPanel` can host (see the playground
 * `PlayerSurface`); audio plays regardless. Requires libVLC to be discoverable at runtime
 * (a VLC install or bundled libVLC) — [createLocalPlayer] throws a `E60020` [TechnicalException]
 * when it is not. `setSpeed` shifts pitch here (libVLC has no pitch-preserving tempo without a
 * filter) — see the module docs.
 *
 * When a [videoContainer] is supplied (the shell's shared video host), the engine adds its video
 * component into it and owns that component's visibility: shown while a non-audio-only source plays,
 * hidden when idle/stopped/audio-only so a co-hosted engine's surface (the WebView) shows through.
 */
internal class JvmLocalPlayer(private val videoContainer: Container? = null) : LocalPlayer {
    private val component: CallbackMediaPlayerComponent = try {
        CallbackMediaPlayerComponent()
    } catch (t: Throwable) {
        throw TechnicalException("E60020", "libVLC not found — install VLC or bundle libVLC", cause = t)
    }
    private val vlc: MediaPlayer get() = component.mediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    @Volatile private var desiredSpeed: Float = 1f

    @Volatile private var audioOnly = false

    @Volatile private var released = false

    /** The last-applied config; [setConfig] diffs against it so only changed fields touch libVLC. */
    @Volatile private var appliedConfig = PlaybackConfig()

    @Volatile private var preferVideoQuality = VideoQualityState.AUTO

    @Volatile private var lastSpuId = -1

    /** The AWT video component for the desktop `PlayerSurface` to embed. */
    val videoComponent: Component get() = component

    /** Run [block] on the Swing EDT (container/visibility mutations must not race the toolkit). */
    private fun onSwing(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    /** Show/hide this engine's video component within the shared container (a no-op without one). */
    private fun showSurface(visible: Boolean) {
        val container = videoContainer ?: return
        onSwing {
            component.isVisible = visible
            container.revalidate()
            container.repaint()
        }
    }

    init {
        videoContainer?.let { c ->
            onSwing {
                component.isVisible = false
                c.add(component)
            }
        }
        vlc.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                // Re-assert per-load settings once libVLC is actually playing (idempotent).
                vlc.controls().setRate(desiredSpeed)
                applyAudioOnly()
                _status.update { it.copy(state = PlaybackState.Ready, playWhenReady = true, error = null) }
            }

            override fun paused(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Ready, playWhenReady = false) }

            override fun finished(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Ended, playWhenReady = false) }

            override fun error(mp: MediaPlayer) =
                _status.update {
                    it.copy(
                        state = PlaybackState.Error, playWhenReady = false,
                        error = PlayerError("E60021", "libVLC playback error"),
                    )
                }

            override fun lengthChanged(mp: MediaPlayer, newLength: Long) =
                _progress.update { it.copy(durationMs = if (newLength > 0L) newLength else Progress.DURATION_UNKNOWN) }
        })
        scope.launch { tick() }
    }

    override fun load(source: PlayableItem.LocalUri, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val uri = source.uri
        // The `playing` event re-asserts rate + audio-only; set them (and volume) now so they hold.
        appliedConfig = config
        desiredSpeed = config.speed.coerceAtLeast(0.01f)
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        vlc.audio().setVolume((config.volume.coerceIn(0f, 1f) * 100f).toInt())
        _status.update {
            it.copy(
                state = PlaybackState.Buffering, playWhenReady = playWhenReady, error = null, videoSize = null,
                volume = config.volume, speed = config.speed,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        // Non-blocking: play() returns immediately; the `playing`/`error` events drive the status. The
        // start position is a media option so it applies before the first frame (no post-play seek).
        val options = if (startPositionMs > 0L) arrayOf(":start-time=${startPositionMs / 1000.0}") else emptyArray()
        vlc.media().play(uri, *options)
        if (!playWhenReady) vlc.controls().setPause(true)
        showSurface(!config.audioOnly)
    }

    override fun play() {
        if (released) return
        vlc.controls().setPause(false)
        vlc.controls().setRate(desiredSpeed)
    }

    override fun pause() {
        if (released) return
        vlc.controls().setPause(true)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        vlc.controls().setTime(clamped)
        _progress.update { it.copy(positionMs = clamped) }
    }

    override fun stop() {
        if (released) return
        vlc.controls().stop()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false, videoSize = null, error = null) }
        showSurface(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config

        if (config.volume != prev.volume) {
            val v = config.volume.coerceIn(0f, 1f)
            vlc.audio().setVolume((v * 100f).toInt())
            _status.update { it.copy(volume = v) }
        }
        if (config.speed != prev.speed) {
            desiredSpeed = config.speed.coerceAtLeast(0.01f)
            vlc.controls().setRate(desiredSpeed)
            _status.update { it.copy(speed = desiredSpeed) }
        }
        val quality = config.preferVideoQuality.first
        if (quality != prev.preferVideoQuality.first) {
            // libVLC has no per-resolution track selection for local files; audio-only is the honored axis.
            preferVideoQuality = quality
            audioOnly = config.audioOnly
            applyAudioOnly()
            _status.update { it.copy(videoSize = if (audioOnly) null else it.videoSize) }
            _videoQuality.update { it.copy(preferredQuality = quality) }
            if (_status.value.state != PlaybackState.Idle) showSurface(!audioOnly)
        }
        if (config.preferCaptionLanguage.first != prev.preferCaptionLanguage.first ||
            config.captionVisible != prev.captionVisible
        ) {
            applyCaption(config)
        }
    }

    /** Map [PlaybackConfig] caption fields onto libVLC's SPU track (`-1` = off). */
    private fun applyCaption(config: PlaybackConfig) {
        val lang = config.preferCaptionLanguage.first
        val id = if (lang == PlaybackConfig.UNDETERMINED_LANGUAGE) -1 else lang.toIntOrNull() ?: -1
        if (id != -1) lastSpuId = id
        vlc.subpictures().setTrack(if (config.captionVisible) (if (id != -1) id else lastSpuId) else -1)
        _captions.update { it.copy(selectedLanguage = id.takeIf { it != -1 }?.toString()) }
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val trackId = id.toIntOrNull() ?: return
        vlc.audio().setTrack(trackId)
        _audioTracks.update { it.copy(selectedId = id) }
    }

    /** Rebuild the capability states from libVLC's current track descriptions (called from the ticker). */
    private fun populateTracks() {
        val dim = vlc.video().videoDimension()
        _videoQuality.value = if (dim == null || dim.height <= 0) {
            VideoQualityState.DEFAULT.copy(preferredQuality = preferVideoQuality)
        } else {
            val opt = VideoQualityState.optionForHeight(minOf(dim.width, dim.height))
            val actual = if (audioOnly) VideoQualityState.AUDIO_ONLY else opt.quality
            VideoQualityState(listOf(opt) + VideoQualityState.DEFAULT.options, actual, preferVideoQuality)
        }

        val subs = vlc.subpictures().trackDescriptions().filter { it.id() != -1 }
        val curSpu = vlc.subpictures().track()
        _captions.value = if (subs.isEmpty()) {
            CaptionState.DEFAULT
        } else {
            CaptionState(subs.map { CaptionTrack(it.id().toString(), it.description()) }, curSpu.takeIf { it != -1 }?.toString())
        }

        val auds = vlc.audio().trackDescriptions().filter { it.id() != -1 }
        val curAud = vlc.audio().track()
        _audioTracks.value = if (auds.size <= 1) {
            AudioTrackState.DEFAULT
        } else {
            AudioTrackState(auds.map { AudioTrack(it.id().toString(), it.description()) }, curAud.takeIf { it != -1 }?.toString())
        }
    }

    /** Disable/enable the libVLC video track so audio-only stops decoding video. */
    private fun applyAudioOnly() {
        val video = vlc.video()
        if (audioOnly) {
            video.setTrack(-1)
        } else {
            video.trackDescriptions().firstOrNull { it.id() != -1 }?.let { video.setTrack(it.id()) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        videoContainer?.let { c -> onSwing { c.remove(component) } }
        component.release()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false) }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (!released && _status.value.state != PlaybackState.Idle) {
                val pos = vlc.status().time().coerceAtLeast(0L)
                val len = vlc.status().length()
                _progress.update { it.copy(positionMs = pos, durationMs = if (len > 0L) len else it.durationMs) }

                val dim = if (audioOnly) null else vlc.video().videoDimension()
                val vs = if (dim != null && dim.width > 0 && dim.height > 0) VideoSize(dim.width, dim.height) else null
                if (vs != null && vs != _status.value.videoSize) _status.update { it.copy(videoSize = vs) }

                populateTracks()
            }
            delay(TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 200L
    }
}

/** The AWT video component of a JVM `LocalPlayer`, or null for a non-JVM/non-VLCJ player. */
fun LocalPlayer.awtVideoComponent(): Component? = (this as? JvmLocalPlayer)?.videoComponent
