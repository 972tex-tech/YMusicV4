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
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * libVLC backend via VLCJ, for JVM desktop. Per the `LocalPlayer` contract the caller drives it on
 * the main (UI) thread; libVLC/VLCJ is itself thread-safe, so the position ticker runs on a
 * background dispatcher. Requires libVLC to be discoverable at runtime (a VLC install or bundled
 * libVLC) — [createLocalPlayer] throws a `E60020` [TechnicalException] when it is not. `setSpeed`
 * shifts pitch here (libVLC has no pitch-preserving tempo without a filter) — see the module docs.
 *
 * Video renders **off-screen**: a `CallbackMediaPlayerComponent` decodes into a native buffer that
 * our [RenderCallback] copies into a [BufferedImage] published on a [LocalVideoSurface] (the twin of
 * the WebView's `JcefOsrSurface`). No AWT component is hosted, so the shell paints the picture as a
 * plain Compose `Image` and popups over the video receive mouse input — unlike a `SwingPanel`, whose
 * interop region swallows the clicks. The engine owns the surface's visibility: shown whenever this is
 * the active engine (including audio-only, which disables the video track and shows a black box) and
 * hidden when idle/stopped so a co-hosted engine (the WebView) shows through. libVLC renders selected
 * captions into the decoded picture.
 */
internal class JvmLocalPlayer(private val videoSurface: LocalVideoSurface? = null) : LocalPlayer {
    /** Copies each RV32 frame into a fresh RGB image and publishes it (mirrors JCEF's `onPaint`). */
    private val renderCallback = RenderCallback { _, buffers, format ->
        val surface = videoSurface ?: return@RenderCallback
        val w = format.width
        val h = format.height
        if (w <= 0 || h <= 0) return@RenderCallback
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        // RV32 is little-endian BGRA; read as ints it lands as 0xAARRGGBB, matching TYPE_INT_RGB.
        buffers[0].order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, minOf(pixels.size, w * h))
        surface.publish(image)
    }

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
            RV32BufferFormat(sourceWidth, sourceHeight)

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit
    }

    private val component: CallbackMediaPlayerComponent = try {
        // imagePainter/videoSurfaceComponent = null: we never host or paint the AWT component; the
        // render callback is the only sink. lockedBuffers = true so the buffer is stable while copied.
        CallbackMediaPlayerComponent(null, null, null, true, null, renderCallback, bufferFormatCallback, null)
    } catch (t: Throwable) {
        throw TechnicalException("E60020", "libVLC not found — install VLC or bundle libVLC", cause = t)
    }
    private val vlc: MediaPlayer get() = component.mediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(PlaybackStatus(state = PlaybackState.Idle))
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

    /** The off-screen frame/visibility endpoint the shell renders as a Compose `Image` (a no-op without one). */
    val surface: LocalVideoSurface? get() = videoSurface

    /** Mark this engine's surface shown/hidden so the shell knows which co-hosted picture to display. */
    private fun showSurface(visible: Boolean) {
        videoSurface?.setVisible(visible)
    }

    init {
        vlc.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                // Re-assert per-load settings once libVLC is actually playing (idempotent).
                vlc.controls().setRate(desiredSpeed)
                applyAudioOnly()
                _status.update { it.copy(state = PlaybackState.Prepared(true, buffering = false, ended = false)) }
            }

            override fun paused(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = false)) }

            override fun finished(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true)) }

            override fun error(mp: MediaPlayer) =
                _status.update {
                    it.copy(
                        state = PlaybackState.Error(PlayerError("E60021", "libVLC playback error")),
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
                state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false), videoSize = null,
                volume = config.volume, speed = config.speed,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        // Non-blocking: play() returns immediately; the `playing`/`error` events drive the status. The
        // start position is a media option so it applies before the first frame (no post-play seek).
        val options = if (startPositionMs > 0L) arrayOf(":start-time=${startPositionMs / 1000.0}") else emptyArray()
        vlc.media().play(uri, *options)
        if (!playWhenReady) vlc.controls().setPause(true)
        showSurface(true)
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
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
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
            _videoQuality.update {
                it.copy(
                    preferredQuality = quality,
                    actualQuality = when {
                        audioOnly -> VideoQualityState.AUDIO_ONLY
                        it.actualQuality == VideoQualityState.AUDIO_ONLY -> VideoQualityState.AUTO
                        else -> it.actualQuality
                    },
                )
            }
            if (_status.value.state is PlaybackState.Prepared) showSurface(true)
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
            VideoQualityState(
                options = VideoQualityState.LOCAL_BASE_OPTIONS,
                actualQuality = if (audioOnly) VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO,
                preferredQuality = preferVideoQuality,
            )
        } else {
            val opt = VideoQualityState.optionForHeight(minOf(dim.width, dim.height))
            val actual = if (audioOnly) VideoQualityState.AUDIO_ONLY else opt.quality
            VideoQualityState(listOf(opt) + VideoQualityState.LOCAL_BASE_OPTIONS, actual, preferVideoQuality)
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
            // No more render callbacks arrive; drop the stale last frame so the surface goes black.
            videoSurface?.clearFrame()
        } else {
            video.trackDescriptions().firstOrNull { it.id() != -1 }?.let { video.setTrack(it.id()) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        component.release()
        videoSurface?.setVisible(false)
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (!released && _status.value.state is PlaybackState.Prepared) {
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

/** The off-screen video surface of a JVM `LocalPlayer`, or null for a non-JVM/non-VLCJ player (or one
 *  created without a surface). The shell renders its [LocalVideoSurface.frame] as a Compose `Image`. */
fun LocalPlayer.localVideoSurface(): LocalVideoSurface? = (this as? JvmLocalPlayer)?.surface
