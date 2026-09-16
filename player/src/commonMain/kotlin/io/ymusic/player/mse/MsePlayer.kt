package io.ymusic.player.mse

import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.AudioTrackState
import io.ymusic.player.CaptionLine
import io.ymusic.player.CaptionState
import io.ymusic.player.CaptionTrack
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackConfig
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.PlayerError
import io.ymusic.player.Progress
import io.ymusic.player.VideoQualityOption
import io.ymusic.player.VideoQualityState
import io.ymusic.player.VideoSize
import io.ymusic.player.YtEngine
import io.ymusic.player.webview.WebViewEvent
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import kmpcommon.error.errorCode
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * YouTube [YtEngine] over engine-js + C MSE. Main-thread control surface; JS work hops to
 * [dispatcher] inside [DynamicEnginePlayer].
 */
class MsePlayer internal constructor(
    private val dispatcher: CoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    private val log: Logger?,
    private val buildPlayer: () -> DynamicEnginePlayer,
) : YtEngine {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var player: DynamicEnginePlayer? = null

    private val _status = MutableStateFlow(
        PlaybackStatus(state = PlaybackState.Uninitialized(isInitializing = false)),
    )
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _captionLines = MutableStateFlow<List<CaptionLine>>(emptyList())
    override val captionLines: StateFlow<List<CaptionLine>> = _captionLines.asStateFlow()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    private val _watchNextSeen = MutableSharedFlow<WebViewEvent.WatchNextSeen>(extraBufferCapacity = 8)
    override val watchNextSeen: SharedFlow<WebViewEvent.WatchNextSeen> = _watchNextSeen.asSharedFlow()

    private var released = false
    private var inited = false
    private var pendingRefresh = false
    private var playWhenReady = false
    private var applied = PlaybackConfig()
    private var statusJob: Job? = null
    private var catalogJob: Job? = null
    private var captionJob: Job? = null
    private var pendingVideoSurface: MseVideoSurface? = null
    private var hasPendingVideoSurface = false
    private var viewportW: Int = 640
    private var viewportH: Int = 360

    override fun refreshOnNext() {
        if (!released) pendingRefresh = true
    }

    override fun warmUp() {
        if (released) return
        val state = _status.value.state
        if (state is PlaybackState.Uninitialized && !state.isInitializing) {
            _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true)) }
            scope.launch { runCatching { ensureInit() } }
        }
    }

    override fun load(
        source: PlayableItem.YtMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        if (released) return
        this.playWhenReady = playWhenReady
        applied = config
        statusJob?.cancel()
        catalogJob?.cancel()
        _status.update {
            it.copy(
                state = PlaybackState.Uninitialized(isInitializing = true),
                volume = config.volume,
                speed = config.speed,
                videoSize = if (config.audioOnly) null else it.videoSize,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        scope.launch {
            try {
                if (pendingRefresh) {
                    pendingRefresh = false
                    inited = false
                }
                val p = ensureInit()
                val quality = config.preferVideoQuality.first
                p.load(
                    source.videoId,
                    log,
                    quality = quality.takeIf { it != VideoQualityState.AUDIO_ONLY },
                    width = viewportW,
                    height = viewportH,
                    audioOnly = config.audioOnly,
                )
                if (startPositionMs > 0L) p.seekTo(startPositionMs, log)
                applyCaptionConfig(config)
                if (playWhenReady) p.play(log) else p.pause(log)
                refreshTracks()
                startListen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(
                    e.errorCode()
                        ?: Regex("""E\d{5}""").find(e.message.orEmpty())?.value
                        ?: "E60033",
                    e.message ?: "mse load failed",
                    e,
                )
            }
        }
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        scope.launch { runCatching { engine().play(log) } }
    }

    override fun pause() {
        if (released) return
        playWhenReady = false
        scope.launch { runCatching { engine().pause(log) } }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val ms = positionMs.coerceAtLeast(0L)
        _progress.update { it.copy(positionMs = ms) }
        scope.launch { runCatching { engine().seekTo(ms, log) } }
    }

    override fun stop() {
        if (released) return
        playWhenReady = false
        statusJob?.cancel()
        catalogJob?.cancel()
        captionJob?.cancel()
        _captionLines.value = emptyList()
        scope.launch { runCatching { engine().stop(log) } }
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        _progress.value = Progress()
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = applied
        applied = config
        _status.update {
            it.copy(
                volume = config.volume.coerceIn(0f, 1f),
                speed = config.speed,
                videoSize = if (config.audioOnly) null else it.videoSize,
            )
        }
        scope.launch {
            runCatching {
                val prevQ = prev.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                    ?: VideoQualityState.AUTO
                val nextQ = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                    ?: VideoQualityState.AUTO
                if (config.audioOnly != prev.audioOnly || nextQ != prevQ) {
                    engine().setQuality(nextQ, config.audioOnly, log)
                }
                applyCaptionConfig(config)
            }
        }
    }

    override fun selectAudioTrack(id: String) = Unit

    /** Android TextureView: pass the window when it appears, null on destroy. JVM bitmap path ignores this. */
    fun setVideoSurface(surface: MseVideoSurface?) {
        pendingVideoSurface = surface
        hasPendingVideoSurface = true
        player?.setVideoSurface(surface)
    }

    /** Display hole in CSS pixels. YouTube Auto ABR uses this. Never report a hole shorter than 360. */
    fun setViewport(width: Int, height: Int) {
        if (released) return
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        if (h < 360) {
            w = (w * 360.0 / h).toInt().coerceAtLeast(1)
            h = 360
        }
        if (w == viewportW && h == viewportH) return
        viewportW = w
        viewportH = h
        val p = player ?: return
        scope.launch { runCatching { p.setViewport(w, h, log) } }
    }

    override suspend fun requestPlayerResponse(videoId: String): String =
        engine().cachedPlayerResponse(videoId, log).toString()

    override suspend fun requestPoToken(videoId: String): String =
        engine().mintContentPoToken(videoId, log)

    override fun release() {
        if (released) return
        released = true
        statusJob?.cancel()
        catalogJob?.cancel()
        captionJob?.cancel()
        scope.launch {
            runCatching { player?.close() }
            scope.cancel()
        }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private suspend fun engine(): DynamicEnginePlayer = withContext(dispatcher) {
        player?.let { return@withContext it }
        val p = buildPlayer()
        player = p
        if (hasPendingVideoSurface) p.setVideoSurface(pendingVideoSurface)
        p
    }

    private suspend fun ensureInit(): DynamicEnginePlayer {
        val p = engine()
        if (!inited) {
            p.init(log, viewportW, viewportH)
            inited = true
        }
        _status.update {
            if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
        }
        return p
    }

    private suspend fun applyCaptionConfig(config: PlaybackConfig) {
        val want = config.captionVisible
        val key = config.preferCaptionLanguage.first
            .takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE }
        val tracks = _captions.value.tracks
        val picked = key?.let { k ->
            tracks.firstOrNull { it.id == k || it.vssId == k || it.languageCode == k }
        } ?: tracks.firstOrNull { it.kind == "asr" } ?: tracks.firstOrNull()
        val vss = if (want) picked?.id ?: key else null
        engine().setCaption(vss, log)
        syncCaptionSession(want)
    }

    private fun syncCaptionSession(visible: Boolean) {
        if (visible) {
            if (captionJob?.isActive == true) return
            captionJob = scope.launch {
                try {
                    engine().collectCaptionLines({ rows ->
                        _captionLines.value = rows.map { CaptionLine(it.id, it.text) }
                    }, log)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Throwable) {
                    _captionLines.value = emptyList()
                }
            }
        } else {
            captionJob?.cancel()
            captionJob = null
            _captionLines.value = emptyList()
        }
    }

    private suspend fun refreshTracks() {
        val tracks = runCatching { engine().captionTracks(log) }.getOrDefault(emptyList())
            .filter { it.kind != "asrt" }
        val selected = applied.preferCaptionLanguage.first
            .takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE && applied.captionVisible }
        _captions.value = CaptionState(
            tracks = tracks.map {
                CaptionTrack(it.languageCode, it.displayName, it.vssId, it.kind)
            },
            selectedLanguage = selected,
        )
        if (applied.captionVisible) applyCaptionConfig(applied)
    }

    private fun startListen() {
        statusJob?.cancel()
        catalogJob?.cancel()
        statusJob = scope.launch {
            try {
                engine().collectStatus({ applyProgress(it) }, log)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
            }
        }
        catalogJob = scope.launch {
            try {
                engine().collectCatalog({ applyCatalog(it) }, log)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyProgress(st: JsonObject) {
        val paused = st.bool("paused", default = true)
        val ended = st.bool("ended", default = false)
        val ready = (st["readyState"] as? JsonPrimitive)?.intOrNull ?: 0
        val buffering = !paused && !ended && ready < 3
        val pos = st.long("positionMs")
        val dur = st.long("durationMs")
        val buf = st.long("bufferedMs")
        val seekable = st.bool("seekable", default = dur > 0L)
        val skStart = st.long("seekableStartMs")
        val skEnd = st.long("seekableEndMs")
        _progress.value = Progress(
            positionMs = pos,
            durationMs = if (dur > 0L) dur else Progress.DURATION_UNKNOWN,
            bufferedPositionMs = buf,
            seekable = seekable,
            seekableStartMs = skStart,
            seekableEndMs = if (skEnd > skStart) skEnd else if (dur > 0L) dur else 0L,
        )
        val w = (st["videoWidth"] as? JsonPrimitive)?.intOrNull ?: 0
        val h = (st["videoHeight"] as? JsonPrimitive)?.intOrNull ?: 0
        val audioOnly = st.bool("audioOnly", default = false)
        val size = if (!audioOnly && w > 0 && h > 0) VideoSize(w, h) else null
        val state = when {
            ended -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            else -> PlaybackState.Prepared(playWhenReady && !paused, buffering, ended = false)
        }
        val live = st.bool("live", default = false)
        _status.update { it.copy(state = state, videoSize = size, live = live) }
    }

    private fun applyCatalog(st: JsonObject) {
        val audioOnly = st.bool("audioOnly", default = false)
        val actual = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            st.str("quality") ?: VideoQualityState.AUTO
        }
        val prefer = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            st.str("preferQuality") ?: applied.preferVideoQuality.first
        }
        val engineOptions = st.qualityOptions().filter { it.quality != VideoQualityState.AUDIO_ONLY }
        val options = listOf(VideoQualityOption(VideoQualityState.AUDIO_ONLY, "Audio-only")) +
            engineOptions.ifEmpty { listOf(VideoQualityOption(VideoQualityState.AUTO, "Auto")) }
        _videoQuality.value = VideoQualityState(
            options = options,
            actualQuality = actual,
            preferredQuality = prefer,
        )
        val cap = st.str("caption")
        if (cap != _captions.value.selectedLanguage) {
            _captions.update { it.copy(selectedLanguage = cap) }
        }
    }

    private fun fail(code: String, message: String, error: Throwable? = null) {
        log?.log(kmpcommon.log.Logger.Level.WARN, "$code $message", error)
        _status.update {
            it.copy(state = PlaybackState.Error(PlayerError(code, message, canRetry = true, needReset = false)))
        }
    }

    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher = QuickJsRuntime.newDispatcher(),
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            videoSurface: MseVideoSurface,
            main: DynamicEngineManager,
            mediaProxy: LocalMediaProxy? = null,
        ): MsePlayer {
            val engineClient = if (mediaProxy != null) SabrProxyHttpClient(client, mediaProxy) else client
            return MsePlayer(dispatcher, videoSurface, logger) {
                DynamicEnginePlayer.create(
                    engineClient, appVersion, dispatcher, logger,
                    cacheStore = cacheStore,
                    cookieJar = cookieJar,
                    settings = settings,
                    videoSurface = videoSurface,
                    main = main,
                )
            }
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.bool(key: String, default: Boolean): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    return p.booleanOrNull ?: (p.content == "true")
}

private fun JsonObject.qualityOptions(): List<VideoQualityOption> {
    val arr = this["qualities"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val q = (o["quality"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        VideoQualityOption(q, (o["label"] as? JsonPrimitive)?.contentOrNull ?: q)
    }
}
