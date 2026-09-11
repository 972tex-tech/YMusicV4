package io.ymusic.player

import io.ymusic.mse.FileAvNative
import io.ymusic.mse.FileAvSink
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities
import kotlin.math.log10

/**
 * JVM desktop local player, ExoPlayer-shaped:
 * - Public API is main/AWT only: enqueue and return (except [release], which waits).
 * - One `player` thread owns libav, copies BGRA, and writes PCM (the audio renderer).
 * - Frames, progress, and engine state hop back to AWT via [SwingUtilities.invokeLater].
 * No C pump thread; [FileAvNative.step] is one tick on the player thread.
 */
internal class JvmLocalPlayer(
    private val videoSurface: LocalVideoSurface?,
) : LocalPlayer,
    FileAvSink {
    private var handle: Long = 0
    private val cmds = LinkedBlockingQueue<() -> Unit>()

    @Volatile private var pumping = false

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

    @Volatile private var released = false

    @Volatile private var stopped = false

    @Volatile private var appliedConfig = PlaybackConfig()

    @Volatile private var durationMs: Long = Progress.DURATION_UNKNOWN

    @Volatile private var loadedPath: String? = null

    @Volatile private var audioOnly = true

    @Volatile private var preferVideoQuality = VideoQualityState.AUDIO_ONLY

    @Volatile private var volume: Float = 1f

    @Volatile private var loop = false

    @Volatile private var line: SourceDataLine? = null

    @Volatile private var lineRate: Int = 0

    @Volatile private var lineCh: Int = 0

    private val frameBufs = arrayOfNulls<BufferedImage>(3)

    @Volatile private var lastPub: BufferedImage? = null

    @Volatile private var streams: List<AvStreamRow> = emptyList()

    private val pendingSeekMs = AtomicLong(-1)
    private val seekQueued = AtomicBoolean(false)
    private val loadGen = AtomicLong(0)
    private val uiEpoch = AtomicLong(0)
    private val framePosted = AtomicBoolean(false)
    private val progressPosted = AtomicBoolean(false)

    @Volatile private var pendingFrame: BufferedImage? = null

    @Volatile private var pendingPosMs: Long = 0

    @Volatile private var pendingDurMs: Long = Progress.DURATION_UNKNOWN

    @Volatile private var pendingVw: Int = 0

    @Volatile private var pendingVh: Int = 0

    val surface: LocalVideoSurface? get() = videoSurface

    private val playerThread = Thread({ playerLoop() }, "player").apply {
        isDaemon = true
        start()
    }

    init {
        dispatch {
            handle = FileAvNative.create()
            if (handle == 0L) {
                onApp {
                    _status.update {
                        it.copy(state = PlaybackState.Error(PlayerError("E60020", "libav file player failed to start")))
                    }
                }
            } else {
                FileAvNative.setSink(handle, this)
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (released) return
        cmds.offer(block)
    }

    /** Listener/UI hop. Always posts so player-thread work never runs re-entrantly on AWT. */
    private fun onApp(block: () -> Unit) {
        SwingUtilities.invokeLater {
            if (!released) block()
        }
    }

    private fun playerLoop() {
        while (!stopped) {
            var cmd = cmds.poll()
            while (cmd != null) {
                cmd.invoke()
                cmd = cmds.poll()
            }
            if (stopped) break
            try {
                if (released) {
                    cmd = cmds.poll(50, TimeUnit.MILLISECONDS)
                    cmd?.invoke()
                } else if (pumping && handle != 0L) {
                    FileAvNative.step(handle)
                    Thread.sleep(4)
                } else {
                    cmd = cmds.poll(50, TimeUnit.MILLISECONDS)
                    cmd?.invoke()
                }
            } catch (_: InterruptedException) {
                // Drain the shutdown command; do not exit before it runs.
            }
        }
    }

    override fun load(
        source: PlayableItem.LocalMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        if (released) return
        appliedConfig = config
        applyConfigFlags(config)
        val path = parseLocalUri(source.uri).toFilePath()
        val gen = loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        pendingSeekMs.set(-1)
        _status.update {
            it.copy(
                state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false),
                videoSize = null,
                volume = volume,
                speed = config.speed,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs.coerceAtLeast(0L))
        showSurface(true)
        dispatch {
            if (handle == 0L || gen != loadGen.get()) return@dispatch
            applyVolume(line, volume)
            FileAvNative.setAudioIndex(handle, -1)
            FileAvNative.setVideoEnabled(handle, !audioOnly)
            FileAvNative.setBlitMax(handle, blitMaxFor(config))
            FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble())
            if (FileAvNative.open(handle, path) != 0) {
                val err = FileAvNative.lastError(handle).ifBlank { "open failed" }
                fail(err)
                return@dispatch
            }
            if (gen != loadGen.get()) return@dispatch
            loadedPath = path
            streams = FileAvNative.streams(handle).mapNotNull(::parseAvStream)
            val dur = FileAvNative.duration(handle)
            durationMs = if (dur > 0) (dur * 1000.0).toLong() else Progress.DURATION_UNKNOWN
            val srcVideo = streams.firstOrNull { it.kind == KIND_VIDEO }
            FileAvNative.seek(handle, startPositionMs.coerceAtLeast(0L) / 1000.0)
            if (playWhenReady) FileAvNative.play(handle) else FileAvNative.pause(handle)
            pumping = playWhenReady
            val size = if (srcVideo != null && !audioOnly && srcVideo.width > 0 && srcVideo.height > 0) {
                VideoSize(srcVideo.width, srcVideo.height)
            } else {
                null
            }
            onApp {
                if (gen != loadGen.get()) return@onApp
                publishTracks(config)
                _status.update {
                    val s = it.state as? PlaybackState.Prepared
                    it.copy(
                        state = s?.copy(buffering = false) ?: PlaybackState.Prepared(playWhenReady, false, false),
                        videoSize = size,
                    )
                }
            }
        }
    }

    override fun play() {
        if (released || loadedPath == null) return
        _status.update {
            val s = it.state
            it.copy(
                state = if (s is PlaybackState.Prepared) {
                    s.copy(playWhenReady = true, ended = false)
                } else {
                    PlaybackState.Prepared(true, buffering = false, ended = false)
                },
            )
        }
        dispatch {
            if (handle != 0L) FileAvNative.play(handle)
            pumping = true
            line?.takeIf { !it.isRunning }?.start()
        }
    }

    override fun pause() {
        if (released) return
        _status.update {
            val s = it.state as? PlaybackState.Prepared ?: return
            it.copy(state = s.copy(playWhenReady = false))
        }
        dispatch {
            pumping = false
            line?.takeIf { it.isRunning }?.stop()
            if (handle != 0L) FileAvNative.pause(handle)
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released || loadedPath == null) return
        val ms = positionMs.coerceAtLeast(0L)
        pendingSeekMs.set(ms)
        uiEpoch.incrementAndGet()
        _progress.update { it.copy(positionMs = ms, durationMs = durationMs) }
        if (!seekQueued.compareAndSet(false, true)) return
        dispatch {
            seekQueued.set(false)
            if (handle == 0L) return@dispatch
            while (!released) {
                val s = pendingSeekMs.getAndSet(-1)
                if (s < 0) break
                FileAvNative.seek(handle, s / 1000.0)
            }
        }
    }

    override fun stop() {
        if (released) return
        loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        pendingSeekMs.set(-1)
        loadedPath = null
        streams = emptyList()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        showSurface(false)
        dispatch {
            pumping = false
            if (handle != 0L) {
                FileAvNative.pause(handle)
                FileAvNative.close(handle)
            }
            closeLine()
        }
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config
        loop = config.loop
        if (config.volume != prev.volume) {
            volume = config.volume.coerceIn(0f, 1f)
            _status.update { it.copy(volume = volume) }
            dispatch { applyVolume(line, volume) }
        }
        if (config.speed != prev.speed) {
            _status.update { it.copy(speed = config.speed) }
            dispatch { if (handle != 0L) FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble()) }
        }
        val quality = config.preferVideoQuality.first
        if (quality != prev.preferVideoQuality.first) {
            val turningVideoOn = prev.audioOnly && !config.audioOnly
            preferVideoQuality = quality
            audioOnly = config.audioOnly
            if (audioOnly) videoSurface?.clearFrame()
            _status.update {
                it.copy(videoSize = if (audioOnly) null else videoSizeFromStreams() ?: it.videoSize)
            }
            publishVideoQuality()
            if (_status.value.state is PlaybackState.Prepared) showSurface(true)
            dispatch {
                if (handle == 0L) return@dispatch
                FileAvNative.setVideoEnabled(handle, !audioOnly)
                FileAvNative.setBlitMax(handle, blitMaxFor(config))
                // Audio-only open never creates vdec; flipping want_video is not enough.
                if (turningVideoOn) reopenAtPlayhead(pumping)
            }
        }
        if (config.preferCaptionLanguage != prev.preferCaptionLanguage ||
            config.captionVisible != prev.captionVisible
        ) {
            publishCaptions(config)
        }
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val index = id.toIntOrNull() ?: return
        _audioTracks.update { it.copy(selectedId = id) }
        val path = loadedPath ?: return
        val playing = (_status.value.state as? PlaybackState.Prepared)?.playWhenReady == true
        dispatch {
            if (handle == 0L) return@dispatch
            FileAvNative.setAudioIndex(handle, index)
            reopenAtPlayhead(playing, path)
        }
    }

    override fun release() {
        if (released) return
        cmds.offer {
            pumping = false
            if (handle != 0L) {
                FileAvNative.destroy(handle)
                handle = 0
            }
            closeLine()
            stopped = true
        }
        released = true
        loadGen.incrementAndGet()
        uiEpoch.incrementAndGet()
        playerThread.interrupt()
        if (Thread.currentThread() !== playerThread) {
            try {
                playerThread.join(5_000)
            } catch (_: InterruptedException) {
            }
        }
        videoSurface?.setVisible(false)
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    override fun onFrame(width: Int, height: Int, bgra: ByteBuffer) {
        if (released || audioOnly) return
        val img = grab(width, height)
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val n = minOf(pixels.size, width * height)
        bgra.order(ByteOrder.LITTLE_ENDIAN)
        bgra.position(0)
        bgra.limit((n * 4).coerceAtMost(bgra.capacity()))
        bgra.asIntBuffer().get(pixels, 0, n)
        lastPub = img
        pendingFrame = img
        if (framePosted.compareAndSet(false, true)) {
            onApp {
                framePosted.set(false)
                val f = pendingFrame ?: return@onApp
                videoSurface?.publish(f)
            }
        }
    }

    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (released) return
        val out = lineFor(rate, channels) ?: return
        if (!out.isRunning && pumping) out.start()
        var off = 0
        while (off < s16.size) {
            val w = out.write(s16, off, s16.size - off)
            if (w <= 0) break
            off += w
        }
    }

    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) {
        if (released) return
        if (pendingSeekMs.get() >= 0) return
        val pos = (positionSec * 1000.0).toLong().coerceAtLeast(0L)
        if (durationSec > 0) durationMs = (durationSec * 1000.0).toLong()
        pendingPosMs = pos
        pendingDurMs = durationMs
        pendingVw = width
        pendingVh = height
        val epoch = uiEpoch.get()
        if (progressPosted.compareAndSet(false, true)) {
            onApp {
                progressPosted.set(false)
                if (epoch != uiEpoch.get() || pendingSeekMs.get() >= 0) return@onApp
                val p = pendingPosMs
                val d = pendingDurMs
                _progress.update {
                    it.copy(positionMs = p, durationMs = d, bufferedPositionMs = d)
                }
                val w = pendingVw
                val h = pendingVh
                if (w > 0 && h > 0 && !audioOnly) {
                    val cur = _status.value.videoSize
                    if (cur == null || cur.width != w || cur.height != h) {
                        _status.update { it.copy(videoSize = VideoSize(w, h)) }
                    }
                }
            }
        }
    }

    override fun onEvent(ended: Boolean, error: String?) {
        if (released) return
        if (error != null && error.isNotEmpty()) {
            fail(error)
            return
        }
        if (!ended) return
        if (loop && loadedPath != null) {
            dispatch {
                if (handle == 0L) return@dispatch
                FileAvNative.seek(handle, 0.0)
                FileAvNative.play(handle)
                pumping = true
            }
            return
        }
        pumping = false
        line?.takeIf { it.isRunning }?.stop()
        val endPos = durationMs
        val gen = loadGen.get()
        onApp {
            if (gen != loadGen.get()) return@onApp
            _progress.update { it.copy(positionMs = if (endPos > 0) endPos else it.positionMs) }
            _status.update {
                it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true))
            }
        }
    }

    private fun applyConfigFlags(config: PlaybackConfig) {
        volume = config.volume.coerceIn(0f, 1f)
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        loop = config.loop
    }

    private fun blitMaxFor(config: PlaybackConfig): Int {
        if (config.audioOnly) return 2
        val h = VideoQualityState.heightForQuality(config.preferVideoQuality.first)
        return h ?: 1920
    }

    /** Player thread. `open` rebuilds decoders from current want_video / audio_index. */
    private fun reopenAtPlayhead(play: Boolean, path: String? = loadedPath) {
        val file = path ?: return
        if (handle == 0L) return
        val pos = FileAvNative.position(handle)
        if (FileAvNative.open(handle, file) != 0) {
            fail(FileAvNative.lastError(handle).ifBlank { "open failed" })
            return
        }
        loadedPath = file
        streams = FileAvNative.streams(handle).mapNotNull(::parseAvStream)
        FileAvNative.seek(handle, pos)
        if (play) FileAvNative.play(handle)
        pumping = play
        val size = videoSizeFromStreams()
        onApp {
            publishTracks(appliedConfig)
            _status.update { it.copy(videoSize = size) }
        }
    }

    private fun videoSizeFromStreams(): VideoSize? {
        if (audioOnly) return null
        val v = streams.firstOrNull { it.kind == KIND_VIDEO } ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        return VideoSize(v.width, v.height)
    }

    private fun publishTracks(config: PlaybackConfig) {
        val auds = streams.filter { it.kind == KIND_AUDIO }
        _audioTracks.value = AudioTrackState(
            auds.map { AudioTrack(it.index.toString(), it.lang.ifBlank { "Audio ${it.index}" }) },
            auds.firstOrNull()?.index?.toString(),
        )
        publishVideoQuality()
        publishCaptions(config)
    }

    private fun publishVideoQuality() {
        val video = streams.firstOrNull { it.kind == KIND_VIDEO }
        val h = video?.let { minOf(it.width, it.height) } ?: 0
        _videoQuality.value = if (h <= 0) {
            VideoQualityState(
                options = VideoQualityState.LOCAL_BASE_OPTIONS,
                actualQuality = if (audioOnly) VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO,
                preferredQuality = preferVideoQuality,
            )
        } else {
            val opt = VideoQualityState.optionForHeight(h)
            VideoQualityState(
                listOf(opt) + VideoQualityState.LOCAL_BASE_OPTIONS,
                if (audioOnly) VideoQualityState.AUDIO_ONLY else opt.quality,
                preferVideoQuality,
            )
        }
    }

    private fun publishCaptions(config: PlaybackConfig) {
        val subs = streams.filter { it.kind == KIND_TEXT }
        _captions.value = if (subs.isEmpty()) {
            CaptionState.DEFAULT
        } else {
            val lang = config.preferCaptionLanguage.first
            val chosen = subs.firstOrNull { it.lang.equals(lang, ignoreCase = true) }
                ?: subs.firstOrNull { it.index.toString() == lang }
                ?: subs.first()
            CaptionState(
                subs.map { CaptionTrack(it.lang.ifBlank { it.index.toString() }, it.codec) },
                if (config.captionVisible) chosen.lang.ifBlank { chosen.index.toString() } else null,
            )
        }
    }

    private fun fail(message: String) {
        pumping = false
        closeLine()
        onApp {
            _status.update { it.copy(state = PlaybackState.Error(PlayerError("E60021", message))) }
        }
        dispatch { if (handle != 0L) FileAvNative.pause(handle) }
    }

    private fun showSurface(visible: Boolean) {
        videoSurface?.setVisible(visible)
    }

    private fun grab(w: Int, h: Int): BufferedImage {
        val shown = lastPub
        for (i in 0..2) {
            val cur = frameBufs[i]
            if (cur != null && cur === shown) continue
            if (cur != null && cur.width == w && cur.height == h) return cur
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            frameBufs[i] = img
            return img
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        frameBufs[0] = img
        return img
    }

    private fun lineFor(rate: Int, channels: Int): SourceDataLine? {
        val cur = line
        if (cur != null && lineRate == rate && lineCh == channels) return cur
        closeLine()
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        return try {
            AudioSystem.getSourceDataLine(format).also {
                it.open(format, rate * channels * 2 / 10)
                applyVolume(it, volume)
                line = it
                lineRate = rate
                lineCh = channels
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun closeLine() {
        val l = line
        line = null
        runCatching { l?.stop() }
        runCatching { l?.close() }
    }
}

private const val KIND_AUDIO = 0
private const val KIND_VIDEO = 1
private const val KIND_TEXT = 2

private data class AvStreamRow(
    val kind: Int,
    val index: Int,
    val width: Int,
    val height: Int,
    val lang: String,
    val codec: String,
)

fun LocalPlayer.localVideoSurface(): LocalVideoSurface? = (this as? JvmLocalPlayer)?.surface

private fun parseLocalUri(raw: String): Uri {
    val t = raw.trim()
    return if (t.contains("://") || t.startsWith("file:")) {
        Uri.parse(t)
    } else {
        UriBuilder().scheme("file").authority("").path(t).build()
    }
}

private fun Uri.toFilePath(): String =
    if (scheme?.toString().equals("file", ignoreCase = true)) path.toString() else toString()

private fun parseAvStream(line: String): AvStreamRow? {
    val p = line.split('|')
    if (p.size < 6) return null
    return AvStreamRow(
        kind = p[0].toIntOrNull() ?: return null,
        index = p[1].toIntOrNull() ?: return null,
        width = p[2].toIntOrNull() ?: 0,
        height = p[3].toIntOrNull() ?: 0,
        lang = p[4],
        codec = p[5],
    )
}

private fun applyVolume(line: SourceDataLine?, volume: Float) {
    val v = volume.coerceIn(0f, 1f)
    val control = try {
        line?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
    } catch (_: Throwable) {
        null
    } ?: return
    val db = if (v <= 0.0001f) control.minimum else (20f * log10(v)).coerceIn(control.minimum, control.maximum)
    control.value = db
}
