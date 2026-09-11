package io.ymusic.player

import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.KmpProcess
import kmpffmpeg.KmpProcessBuilder
import kmpffmpeg.metadata.FfmpegInfoParseException
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmpffmpeg.metadata.MediaFileMetadata
import kmpffmpeg.metadata.StreamMetadata
import kmpffmpeg.metadata.isAttachedThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.EOFException
import okio.buffer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * JVM desktop local player: the provisioned `ffmpeg` binary decodes, Java Sound plays PCM, and
 * video frames land on [LocalVideoSurface] as BGRA→RGB images (same Compose `Image` path as before).
 *
 * Pause stops consuming the pipes so ffmpeg blocks on a full OS buffer; seek/speed/track changes
 * restart the child at the new position. `setSpeed` shifts pitch (`asetrate`), matching the old
 * libVLC rate behaviour. Captions are burned in with ffmpeg's `subtitles` filter when visible.
 */
internal class JvmLocalPlayer(
    private val videoSurface: LocalVideoSurface?,
    private val ffmpegPath: String,
) : LocalPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val getter = FfmpegMetadataGetter(ffmpegPath)
    private val gen = AtomicInteger(0)

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

    @Volatile private var playing = false

    @Volatile private var audioOnly = true

    @Volatile private var preferVideoQuality = VideoQualityState.AUDIO_ONLY

    @Volatile private var selectedAudioIndex: Int? = null

    @Volatile private var lastCaptionIndex: Int? = null

    @Volatile private var pcmBytes: Long = 0

    @Volatile private var originMs: Long = 0

    @Volatile private var durationMs: Long = Progress.DURATION_UNKNOWN

    @Volatile private var speed: Float = 1f

    @Volatile private var volume: Float = 1f

    @Volatile private var loop = false

    @Volatile private var line: SourceDataLine? = null

    @Volatile private var audioProc: KmpProcess? = null

    @Volatile private var videoProc: KmpProcess? = null

    @Volatile private var loadedUri: Uri? = null

    @Volatile private var loadedMeta: MediaFileMetadata? = null
    private var loadJob: Job? = null

    val surface: LocalVideoSurface? get() = videoSurface

    private fun showSurface(visible: Boolean) {
        videoSurface?.setVisible(visible)
    }

    override fun load(
        source: PlayableItem.LocalMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        if (released) return
        appliedConfig = config
        speed = config.speed.coerceAtLeast(0.01f)
        volume = config.volume.coerceIn(0f, 1f)
        audioOnly = config.audioOnly
        preferVideoQuality = config.preferVideoQuality.first
        loop = config.loop
        selectedAudioIndex = null
        playing = playWhenReady
        originMs = startPositionMs.coerceAtLeast(0L)
        pcmBytes = 0
        _status.update {
            it.copy(
                state = PlaybackState.Prepared(playWhenReady, buffering = true, ended = false),
                videoSize = null,
                volume = volume,
                speed = speed,
            )
        }
        _progress.value = Progress(positionMs = originMs)
        showSurface(true)
        val uri = parseLocalUri(source.uri)
        startSession(uri, originMs, playWhenReady, probe = true)
    }

    override fun play() {
        if (released) return
        playing = true
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
        if (released) return
        playing = false
        line?.takeIf { it.isRunning }?.stop()
        _status.update {
            val s = it.state as? PlaybackState.Prepared ?: return
            it.copy(state = s.copy(playWhenReady = false))
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val uri = loadedUri ?: return
        originMs = positionMs.coerceAtLeast(0L)
        pcmBytes = 0
        _progress.update { it.copy(positionMs = originMs) }
        startSession(uri, originMs, playing, probe = false)
    }

    override fun stop() {
        if (released) return
        playing = false
        tearDown(gen.incrementAndGet())
        loadedUri = null
        loadedMeta = null
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        showSurface(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config
        loop = config.loop
        if (config.volume != prev.volume) {
            volume = config.volume.coerceIn(0f, 1f)
            applyVolume(line, volume)
            _status.update { it.copy(volume = volume) }
        }
        val quality = config.preferVideoQuality.first
        val speedChanged = config.speed != prev.speed
        val videoChanged = quality != prev.preferVideoQuality.first
        val captionChanged = config.preferCaptionLanguage.first != prev.preferCaptionLanguage.first ||
            config.captionVisible != prev.captionVisible
        if (speedChanged) {
            speed = config.speed.coerceAtLeast(0.01f)
            _status.update { it.copy(speed = speed) }
        }
        if (videoChanged) {
            preferVideoQuality = quality
            audioOnly = config.audioOnly
            if (audioOnly) videoSurface?.clearFrame()
            _status.update { it.copy(videoSize = if (audioOnly) null else it.videoSize) }
            publishVideoQuality(loadedMeta)
            if (_status.value.state is PlaybackState.Prepared) showSurface(true)
        }
        if (captionChanged) publishCaptions(loadedMeta, config)
        if ((speedChanged || videoChanged || captionChanged) && loadedUri != null) {
            startSession(loadedUri!!, mediaPositionMs(), playing, probe = false)
        }
    }

    override fun selectAudioTrack(id: String) {
        if (released) return
        val index = id.toIntOrNull() ?: return
        selectedAudioIndex = index
        _audioTracks.update { it.copy(selectedId = id) }
        val uri = loadedUri ?: return
        startSession(uri, mediaPositionMs(), playing, probe = false)
    }

    override fun release() {
        if (released) return
        released = true
        playing = false
        tearDown(gen.incrementAndGet())
        scope.cancel()
        videoSurface?.setVisible(false)
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private fun startSession(uri: Uri, startMs: Long, playWhenReady: Boolean, probe: Boolean) {
        val session = gen.incrementAndGet()
        tearDown(session)
        loadJob?.cancel()
        originMs = startMs.coerceAtLeast(0L)
        pcmBytes = 0
        loadJob = scope.launch {
            try {
                val meta = if (probe || loadedMeta == null || loadedUri != uri) {
                    getter.getMetadata(uri)
                } else {
                    loadedMeta!!
                }
                if (session != gen.get()) return@launch
                loadedUri = uri
                loadedMeta = meta
                durationMs = meta.container.duration ?: Progress.DURATION_UNKNOWN
                publishTracks(meta, appliedConfig)
                _progress.update {
                    it.copy(positionMs = originMs, durationMs = durationMs, bufferedPositionMs = durationMs)
                }
                val spec = uri.toFileSpec()
                val audio = pickAudio(meta)
                val video = pickVideo(meta)
                val caption = pickCaption(meta, appliedConfig)
                if (audio == null && video == null) {
                    fail("E60021", "no audio or video stream")
                    return@launch
                }
                if (audio != null) {
                    val proc = startFfmpeg(audioArgs(spec, audio.index, originMs, speed))
                    if (session != gen.get()) {
                        proc.close()
                        return@launch
                    }
                    audioProc = proc
                    launch { pumpAudio(session, proc, playWhenReady) }
                }
                if (video != null) {
                    val proc = startFfmpeg(
                        videoArgs(spec, video, originMs, caption, appliedConfig.captionVisible),
                    )
                    if (session != gen.get()) {
                        proc.close()
                        return@launch
                    }
                    videoProc = proc
                    val size = VideoSize(video.width ?: 0, video.height ?: 0)
                    if (size.width > 0 && size.height > 0) {
                        _status.update { it.copy(videoSize = size) }
                    }
                    launch { pumpVideo(session, proc, video) }
                }
                _status.update {
                    it.copy(
                        state = PlaybackState.Prepared(playWhenReady, buffering = false, ended = false),
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (session != gen.get()) return@launch
                val msg = (t as? FfmpegInfoParseException)?.stderrTail ?: t.message
                fail("E60021", msg ?: "ffmpeg playback error")
            }
        }
    }

    private suspend fun pumpAudio(session: Int, proc: KmpProcess, startPlaying: Boolean) {
        val format = AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false)
        val out = try {
            AudioSystem.getSourceDataLine(format).also {
                it.open(format, LINE_BUFFER)
                applyVolume(it, volume)
                if (startPlaying) it.start()
            }
        } catch (t: Throwable) {
            if (session == gen.get()) fail("E60021", t.message ?: "audio output failed")
            return
        }
        line = out
        val pcm = ByteArray(PCM_CHUNK)
        val src = proc.stdout.buffer()
        try {
            while (scope.isActive && session == gen.get()) {
                if (!playing) {
                    delay(20)
                    continue
                }
                if (!out.isRunning) out.start()
                val n = try {
                    src.read(pcm)
                } catch (_: IOException) {
                    break
                }
                if (n < 0) {
                    onEnded(session)
                    return
                }
                if (n == 0) continue
                var off = 0
                while (off < n && session == gen.get()) {
                    val w = out.write(pcm, off, n - off)
                    if (w <= 0) break
                    off += w
                }
                pcmBytes += n.toLong()
                if (session == gen.get()) {
                    _progress.update {
                        it.copy(positionMs = mediaPositionMs(), durationMs = durationMs)
                    }
                }
            }
        } finally {
            runCatching { src.close() }
            if (line === out) line = null
            runCatching { out.stop() }
            runCatching { out.close() }
        }
    }

    private suspend fun pumpVideo(session: Int, proc: KmpProcess, video: StreamMetadata.Video) {
        val w = video.width ?: return
        val h = video.height ?: return
        if (w <= 0 || h <= 0) return
        val frameBytes = w * h * 4
        val fps = video.fps?.takeIf { it > 0.0 } ?: 25.0
        val frame = ByteArray(frameBytes)
        val src = proc.stdout.buffer()
        var index = 0L
        try {
            while (scope.isActive && session == gen.get()) {
                if (!playing || audioOnly) {
                    delay(20)
                    continue
                }
                try {
                    src.readFully(frame)
                } catch (_: EOFException) {
                    return
                } catch (_: IOException) {
                    return
                }
                val mediaMs = originMs + (index * 1000.0 / fps).roundToLong()
                index++
                while (scope.isActive && session == gen.get() && playing && !audioOnly) {
                    val ahead = mediaMs - mediaPositionMs()
                    if (ahead <= 25) break
                    delay(ahead.coerceAtMost(40))
                }
                if (mediaMs + 80 < mediaPositionMs()) continue // late: drop
                if (session != gen.get() || audioOnly) continue
                val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val pixels = (image.raster.dataBuffer as DataBufferInt).data
                ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                    .get(pixels, 0, minOf(pixels.size, w * h))
                videoSurface?.publish(image)
            }
        } finally {
            runCatching { src.close() }
        }
    }

    private fun onEnded(session: Int) {
        if (session != gen.get()) return
        if (loop && loadedUri != null) {
            originMs = 0
            pcmBytes = 0
            startSession(loadedUri!!, 0, playWhenReady = true, probe = false)
            return
        }
        playing = false
        line?.takeIf { it.isRunning }?.stop()
        _progress.update {
            it.copy(positionMs = if (durationMs > 0) durationMs else mediaPositionMs())
        }
        _status.update {
            it.copy(state = PlaybackState.Prepared(false, buffering = false, ended = true))
        }
    }

    private fun fail(code: String, message: String) {
        playing = false
        tearDown(gen.incrementAndGet())
        _status.update {
            it.copy(state = PlaybackState.Error(PlayerError(code, message)))
        }
    }

    private fun tearDown(keepGen: Int) {
        // keepGen is the session that should survive; close anything older by dropping refs.
        val a = audioProc
        val v = videoProc
        audioProc = null
        videoProc = null
        a?.close()
        v?.close()
        val l = line
        line = null
        runCatching { l?.stop() }
        runCatching { l?.close() }
        if (keepGen != gen.get()) return
    }

    private fun startFfmpeg(args: List<String>): KmpProcess {
        val proc = KmpProcessBuilder(ffmpegPath).appendArgs(args).start()
        proc.scope.launch { runCatching { proc.stderr.buffer().readUtf8() } }
        return proc
    }

    private fun audioArgs(spec: String, streamIndex: Int, startMs: Long, rate: Float): List<String> {
        val tempo = rate.coerceAtLeast(0.01f)
        val asetrate = (SAMPLE_RATE * tempo).toInt().coerceAtLeast(1)
        return listOf(
            "-hide_banner", "-nostdin", "-loglevel", "error",
            "-ss", formatSeconds(startMs),
            "-i", spec,
            "-vn", "-map", "0:$streamIndex",
            "-af", "aresample=$SAMPLE_RATE,asetrate=$asetrate,aresample=$SAMPLE_RATE," +
                "aformat=sample_fmts=s16:channel_layouts=stereo",
            "-f", "s16le", "pipe:1",
        )
    }

    private fun videoArgs(
        spec: String,
        video: StreamMetadata.Video,
        startMs: Long,
        caption: StreamMetadata.Text?,
        captionOn: Boolean,
    ): List<String> {
        val vf = buildString {
            if (captionOn && caption != null) {
                val si = loadedMeta?.streams?.filterIsInstance<StreamMetadata.Text>().orEmpty()
                    .indexOfFirst { it.index == caption.index }
                    .coerceAtLeast(0)
                append("subtitles=")
                append(escapeFilterPath(filePathOf(spec)))
                append(":si=")
                append(si)
                append(",")
            }
            append("format=bgra")
        }
        return listOf(
            "-hide_banner", "-nostdin", "-loglevel", "error",
            "-ss", formatSeconds(startMs),
            "-i", spec,
            "-an", "-map", "0:${video.index}",
            "-vf", vf,
            "-f", "rawvideo", "pipe:1",
        )
    }

    private fun pickAudio(meta: MediaFileMetadata): StreamMetadata.Audio? {
        val all = meta.streams.filterIsInstance<StreamMetadata.Audio>()
        if (all.isEmpty()) return null
        val want = selectedAudioIndex
        return all.firstOrNull { it.index == want } ?: all.first()
    }

    private fun pickVideo(meta: MediaFileMetadata): StreamMetadata.Video? {
        if (audioOnly) return null
        return meta.streams.filterIsInstance<StreamMetadata.Video>().firstOrNull { !it.isAttachedThumbnail() }
    }

    private fun pickCaption(meta: MediaFileMetadata, config: PlaybackConfig): StreamMetadata.Text? {
        val all = meta.streams.filterIsInstance<StreamMetadata.Text>()
        if (all.isEmpty()) return null
        val lang = config.preferCaptionLanguage.first
        val byId = lang.toIntOrNull()?.let { id -> all.firstOrNull { it.index == id } }
        val byLang = all.firstOrNull { it.metadataMap.language.equals(lang, ignoreCase = true) }
        val chosen = when {
            lang != PlaybackConfig.UNDETERMINED_LANGUAGE -> byId ?: byLang
            else -> lastCaptionIndex?.let { id -> all.firstOrNull { it.index == id } }
        } ?: all.first()
        lastCaptionIndex = chosen.index
        return chosen
    }

    private fun publishTracks(meta: MediaFileMetadata, config: PlaybackConfig) {
        publishVideoQuality(meta)
        publishCaptions(meta, config)
        val auds = meta.streams.filterIsInstance<StreamMetadata.Audio>()
        _audioTracks.value = if (auds.size <= 1) {
            AudioTrackState.DEFAULT
        } else {
            val sel = pickAudio(meta)?.index
            selectedAudioIndex = sel
            AudioTrackState(
                auds.map { AudioTrack(it.index.toString(), it.metadataMap.language ?: "Audio ${it.index}") },
                sel?.toString(),
            )
        }
    }

    private fun publishVideoQuality(meta: MediaFileMetadata?) {
        val video = meta?.streams?.filterIsInstance<StreamMetadata.Video>()
            ?.firstOrNull { !it.isAttachedThumbnail() }
        val h = video?.let { minOf(it.width ?: 0, it.height ?: 0) } ?: 0
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

    private fun publishCaptions(meta: MediaFileMetadata?, config: PlaybackConfig) {
        val subs = meta?.streams?.filterIsInstance<StreamMetadata.Text>().orEmpty()
        _captions.value = if (subs.isEmpty()) {
            CaptionState.DEFAULT
        } else {
            val chosen = pickCaption(meta!!, config)
            CaptionState(
                subs.map {
                    CaptionTrack(it.index.toString(), it.metadataMap.language ?: it.codec)
                },
                if (config.captionVisible) chosen?.index?.toString() else null,
            )
        }
    }

    private fun mediaPositionMs(): Long {
        val pcmMs = pcmBytes * 1000L / BYTES_PER_SECOND
        return originMs + (pcmMs * speed).toLong()
    }

    companion object {
        const val SAMPLE_RATE = 48_000f
        const val CHANNELS = 2
        const val BYTES_PER_SECOND = 48_000L * 2 * 2
        const val PCM_CHUNK = 4096
        const val LINE_BUFFER = 48_000 * 2 * 2 / 10 // ~100 ms
    }
}

/** The off-screen video surface of a JVM `LocalPlayer`, or null when the engine has none. */
fun LocalPlayer.localVideoSurface(): LocalVideoSurface? = (this as? JvmLocalPlayer)?.surface

private fun parseLocalUri(raw: String): Uri {
    val t = raw.trim()
    return if (t.contains("://") || t.startsWith("file:")) {
        Uri.parse(t)
    } else {
        UriBuilder().scheme("file").authority("").path(t).build()
    }
}

/** ffmpeg's file protocol wants the decoded path, not a percent-encoded URI. */
private fun Uri.toFileSpec(): String =
    if (scheme?.toString().equals("file", ignoreCase = true)) "file:$path" else toString()

private fun filePathOf(spec: String): String =
    if (spec.startsWith("file:")) spec.removePrefix("file:") else spec

private fun formatSeconds(ms: Long): String = (ms.coerceAtLeast(0L) / 1000.0).toString()

private fun escapeFilterPath(path: String): String =
    path.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")

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
