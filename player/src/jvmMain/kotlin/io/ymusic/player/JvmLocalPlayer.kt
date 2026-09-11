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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * JVM desktop local player: in-process libav (`FileAvNative` / `file_av.c`) shares the packed
 * libav DSOs with MSE leftover, but is a file host — PTS vs a monotonic clock, not two ffmpeg
 * pipes. Java Sound is the DAC; frames land on [LocalVideoSurface] as BGRA→RGB. 4K is downscaled
 * (max edge 1920, or the selected quality ladder). Captions are listed, not burned in.
 */
internal class JvmLocalPlayer(
    private val videoSurface: LocalVideoSurface?,
) : LocalPlayer,
    FileAvSink {
    private val handle = FileAvNative.create()

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

    val surface: LocalVideoSurface? get() = videoSurface

    init {
        if (handle == 0L) {
            _status.update {
                it.copy(state = PlaybackState.Error(PlayerError("E60020", "libav file player failed to start")))
            }
        } else {
            FileAvNative.setSink(handle, this)
        }
    }

    override fun load(
        source: PlayableItem.LocalMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        if (released || handle == 0L) return
        appliedConfig = config
        applyConfigFlags(config)
        val path = parseLocalUri(source.uri).toFilePath()
        FileAvNative.setAudioIndex(handle, -1)
        FileAvNative.setVideoEnabled(handle, !audioOnly)
        FileAvNative.setBlitMax(handle, blitMaxFor(config))
        FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble())
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
        if (FileAvNative.open(handle, path) != 0) {
            fail("E60021", FileAvNative.lastError(handle).ifBlank { "open failed" })
            return
        }
        loadedPath = path
        streams = FileAvNative.streams(handle).mapNotNull(::parseAvStream)
        val dur = FileAvNative.duration(handle)
        durationMs = if (dur > 0) (dur * 1000.0).toLong() else Progress.DURATION_UNKNOWN
        publishTracks(config)
        val srcVideo = streams.firstOrNull { it.kind == KIND_VIDEO }
        if (srcVideo != null && !audioOnly && srcVideo.width > 0 && srcVideo.height > 0) {
            _status.update { it.copy(videoSize = VideoSize(srcVideo.width, srcVideo.height)) }
        }
        FileAvNative.seek(handle, startPositionMs.coerceAtLeast(0L) / 1000.0)
        _status.update {
            val s = it.state as? PlaybackState.Prepared
            it.copy(state = s?.copy(buffering = false) ?: PlaybackState.Prepared(playWhenReady, false, false))
        }
        if (playWhenReady) FileAvNative.play(handle) else FileAvNative.pause(handle)
    }

    override fun play() {
        if (released || handle == 0L || loadedPath == null) return
        FileAvNative.play(handle)
        line?.takeIf { !it.isRunning }?.start()
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
    }

    override fun pause() {
        if (released || handle == 0L) return
        FileAvNative.pause(handle)
        line?.takeIf { it.isRunning }?.stop()
        _status.update {
            val s = it.state as? PlaybackState.Prepared ?: return
            it.copy(state = s.copy(playWhenReady = false))
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released || handle == 0L || loadedPath == null) return
        FileAvNative.seek(handle, positionMs.coerceAtLeast(0L) / 1000.0)
        _progress.update { it.copy(positionMs = positionMs.coerceAtLeast(0L), durationMs = durationMs) }
    }

    override fun stop() {
        if (released || handle == 0L) return
        FileAvNative.pause(handle)
        FileAvNative.close(handle)
        loadedPath = null
        streams = emptyList()
        closeLine()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        showSurface(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released || handle == 0L) return
        val prev = appliedConfig
        appliedConfig = config
        loop = config.loop
        if (config.volume != prev.volume) {
            volume = config.volume.coerceIn(0f, 1f)
            applyVolume(line, volume)
            _status.update { it.copy(volume = volume) }
        }
        if (config.speed != prev.speed) {
            FileAvNative.setSpeed(handle, config.speed.coerceAtLeast(0.05f).toDouble())
            _status.update { it.copy(speed = config.speed) }
        }
        val quality = config.preferVideoQuality.first
        if (quality != prev.preferVideoQuality.first) {
            preferVideoQuality = quality
            audioOnly = config.audioOnly
            FileAvNative.setVideoEnabled(handle, !audioOnly)
            FileAvNative.setBlitMax(handle, blitMaxFor(config))
            if (audioOnly) videoSurface?.clearFrame()
            _status.update { it.copy(videoSize = if (audioOnly) null else it.videoSize) }
            publishVideoQuality()
            if (_status.value.state is PlaybackState.Prepared) showSurface(true)
        }
        if (config.preferCaptionLanguage != prev.preferCaptionLanguage ||
            config.captionVisible != prev.captionVisible
        ) {
            publishCaptions(config)
        }
    }

    override fun selectAudioTrack(id: String) {
        if (released || handle == 0L) return
        val index = id.toIntOrNull() ?: return
        FileAvNative.setAudioIndex(handle, index)
        _audioTracks.update { it.copy(selectedId = id) }
        val path = loadedPath ?: return
        val pos = FileAvNative.position(handle)
        val playing = (_status.value.state as? PlaybackState.Prepared)?.playWhenReady == true
        FileAvNative.open(handle, path)
        FileAvNative.seek(handle, pos)
        if (playing) FileAvNative.play(handle)
    }

    override fun release() {
        if (released) return
        released = true
        closeLine()
        if (handle != 0L) FileAvNative.destroy(handle)
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
        videoSurface?.publish(img)
    }

    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (released) return
        val out = lineFor(rate, channels) ?: return
        if (!out.isRunning && (_status.value.state as? PlaybackState.Prepared)?.playWhenReady == true) {
            out.start()
        }
        var off = 0
        while (off < s16.size) {
            val w = out.write(s16, off, s16.size - off)
            if (w <= 0) break
            off += w
        }
    }

    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) {
        if (released) return
        val pos = (positionSec * 1000.0).toLong().coerceAtLeast(0L)
        if (durationSec > 0) durationMs = (durationSec * 1000.0).toLong()
        _progress.update {
            it.copy(positionMs = pos, durationMs = durationMs, bufferedPositionMs = durationMs)
        }
        if (width > 0 && height > 0 && !audioOnly) {
            val cur = _status.value.videoSize
            if (cur == null || cur.width != width || cur.height != height) {
                _status.update { it.copy(videoSize = VideoSize(width, height)) }
            }
        }
    }

    override fun onEvent(ended: Boolean, error: String?) {
        if (released) return
        if (error != null && error.isNotEmpty()) {
            fail("E60021", error)
            return
        }
        if (!ended) return
        if (loop && loadedPath != null) {
            FileAvNative.seek(handle, 0.0)
            FileAvNative.play(handle)
            return
        }
        line?.takeIf { it.isRunning }?.stop()
        _progress.update { it.copy(positionMs = if (durationMs > 0) durationMs else it.positionMs) }
        _status.update {
            it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true))
        }
    }

    private fun applyConfigFlags(config: PlaybackConfig) {
        volume = config.volume.coerceIn(0f, 1f)
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        loop = config.loop
        applyVolume(line, volume)
    }

    private fun blitMaxFor(config: PlaybackConfig): Int {
        if (config.audioOnly) return 2
        val h = VideoQualityState.heightForQuality(config.preferVideoQuality.first)
        return h ?: 1920
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

    private fun fail(code: String, message: String) {
        FileAvNative.pause(handle)
        closeLine()
        _status.update { it.copy(state = PlaybackState.Error(PlayerError(code, message))) }
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
