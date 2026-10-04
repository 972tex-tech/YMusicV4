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
import io.ymusic.player.WatchNextSeen
import io.ymusic.player.YtEngine
import io.ymusic.player.requireMainThread
import io.ymusic.quickjs.QuickJsRuntime
import io.ymusic.settings.SettingsStore
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import io.ymusic.ytmedia.proxy.SabrProxyHttpClient
import kmpcommon.error.errorCode
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * YouTube [YtEngine] over engine-js + C MSE. Control and [scope] are main-thread; JS hops inside
 * [DynamicEnginePlayer] pipes. [DynamicEnginePlayer.create] runs on [engineDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsePlayer internal constructor(
    dispatcher: CoroutineDispatcher,
    private val engineDispatcher: CloseableCoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    private val log: Logger?,
    private val buildPlayer: () -> DynamicEnginePlayer,
) : YtEngine {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val initMutex = Mutex()
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

    private val _watchNextSeen = MutableSharedFlow<WatchNextSeen>(extraBufferCapacity = 8)
    override val watchNextSeen: SharedFlow<WatchNextSeen> = _watchNextSeen.asSharedFlow()

    private var released = false
    private var inited = false
    private var pendingRefresh = false
    private var playWhenReady = false
    private var applied = PlaybackConfig()
    private var pendingLoad: LoadReq? = null
    private var loadingVideoId: String? = null
    private var loadedVideoId: String? = null
    private var initJob: Job? = null
    private var loadJob: Job? = null
    private var loadTimeoutJob: Job? = null
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

    override fun init() {
        requireMainThread()
        if (released) return
        val state = _status.value.state
        if (state is PlaybackState.Uninitialized && !state.isInitializing) {
            _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true)) }
            initJob = scope.launch {
                try {
                    ensureInit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(
                        e.errorCode()
                            ?: Regex("""E\d{5}""").find(e.message.orEmpty())?.value
                            ?: "E60033",
                        e.message ?: "mse init failed",
                        e,
                    )
                }
            }
        }
    }

    override fun reset() {
        requireMainThread()
        if (released) return
        pendingRefresh = false
        pendingLoad = null
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadJob?.cancel()
        captionJob?.cancel()
        initJob?.cancel()
        statusJob?.cancel()
        catalogJob?.cancel()
        captionJob = null
        statusJob = null
        catalogJob = null
        inited = false
        _captionLines.value = emptyList()
        _progress.value = Progress()
        _status.update {
            it.copy(state = PlaybackState.Uninitialized(isInitializing = true), videoSize = null)
        }
        val old = player
        player = null
        initJob = scope.launch {
            try {
                runCatching { old?.close() }
                ensureInit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(
                    e.errorCode()
                        ?: Regex("""E\d{5}""").find(e.message.orEmpty())?.value
                        ?: "E60033",
                    e.message ?: "mse reset failed",
                    e,
                )
            }
        }
    }

    override fun preload(source: PlayableItem.YtMedia?) {
        requireMainThread()
        source ?: return
        if (released) return
        if (!_status.value.state.readyToLoad) return
        val p = checkNotNull(player)
        scope.launch { runCatching { p.preload(source.videoId, log) } }
    }

    override fun load(
        source: PlayableItem.YtMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        requireMainThread()
        if (released) return
        pendingLoad = LoadReq(source, startPositionMs)
        this.playWhenReady = playWhenReady
        this.applied = config
        loadingVideoId = source.videoId
        loadTimeoutJob?.cancel()
        _progress.value = Progress(positionMs = startPositionMs)
        videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        videoSurface.setAudioEffect(
            FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } },
            config.bassBoostDb.coerceIn(0f, 12f),
            config.virtualizer.coerceIn(0f, 2f),
        )
        if (!_status.value.state.readyToLoad) return
        startLoad()
    }

    private fun startLoad() {
        val req = pendingLoad ?: return
        val source = req.source
        val p = checkNotNull(player)
        loadJob?.cancel()
        loadTimeoutJob?.cancel()
        loadJob = scope.launch {
            try {
                val config = applied
                val quality = config.preferVideoQuality.first
                p.load(
                    source.videoId,
                    log,
                    quality = quality.takeIf { it != VideoQualityState.AUDIO_ONLY },
                    width = viewportW,
                    height = viewportH,
                    audioOnly = config.audioOnly,
                    startPositionMs = req.startPositionMs,
                )
                loadTimeoutJob = launch {
                    delay(LOAD_TIMEOUT_MS)
                    if (loadingVideoId != null) {
                        loadingVideoId = null
                        loadedVideoId = null
                        fail("E60051", "Load timeout")
                    }
                }
                applyCaptionConfig(p, config)
                p.setLoop(config.loop && !source.live, log)
                if (playWhenReady) p.play(log) else p.pause(log)
                p.setSpeedPitch(
                    config.speed.coerceIn(0.25f, 3f),
                    config.pitchFactor.coerceIn(0.5f, 2f),
                    log,
                )
                refreshTracks(p)
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
        requireMainThread()
        if (released) return
        playWhenReady = true
        val p = checkNotNull(player)
        scope.launch { runCatching { p.play(log) } }
    }

    override fun pause() {
        requireMainThread()
        if (released) return
        playWhenReady = false
        val p = checkNotNull(player)
        scope.launch { runCatching { p.pause(log) } }
    }

    override fun seekTo(positionMs: Long) {
        requireMainThread()
        if (released) return
        val p = checkNotNull(player)
        scope.launch { runCatching { p.seekTo(positionMs, log) } }
    }

    override fun stop() {
        requireMainThread()
        if (released) return
        playWhenReady = false
        pendingLoad = null
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadJob?.cancel()
        captionJob?.cancel()
        _captionLines.value = emptyList()
        val p = player
        if (p != null) scope.launch { runCatching { p.stop(log) } }
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        _progress.value = Progress()
    }

    override fun setConfig(config: PlaybackConfig) {
        requireMainThread()
        if (released) return
        val prev = applied
        applied = config
        if (config.volume != prev.volume) {
            videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        }
        if (config.eqBands != prev.eqBands ||
            config.bassBoostDb != prev.bassBoostDb ||
            config.virtualizer != prev.virtualizer
        ) {
            videoSurface.setAudioEffect(
                FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } },
                config.bassBoostDb.coerceIn(0f, 12f),
                config.virtualizer.coerceIn(0f, 2f),
            )
        }
        val p = player ?: return
        scope.launch {
            runCatching {
                val prevQ = prev.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                    ?: VideoQualityState.AUTO
                val nextQ = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                    ?: VideoQualityState.AUTO
                if (config.audioOnly != prev.audioOnly || nextQ != prevQ) {
                    p.setQuality(nextQ, config.audioOnly, log)
                }
                if (config.loop != prev.loop) {
                    p.setLoop(config.loop, log)
                }
                if (config.speed != prev.speed || config.pitchFactor != prev.pitchFactor) {
                    p.setSpeedPitch(
                        config.speed.coerceIn(0.25f, 3f),
                        config.pitchFactor.coerceIn(0.5f, 2f),
                        log,
                    )
                }
                applyCaptionConfig(p, config)
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
        requireMainThread()
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
        ensureInit().cachedPlayerResponse(videoId, log).toString()

    override suspend fun requestPoToken(videoId: String): String =
        ensureInit().mintContentPoToken(videoId, log)

    override fun pcmLevels(out: FloatArray): Int =
        if (released || out.isEmpty()) 0 else videoSurface.pcmLevels(out)

    override fun presentedFrames(): Int =
        if (released) 0 else videoSurface.presentedFrames()

    override fun release() {
        requireMainThread()
        if (released) return
        released = true
        pendingLoad = null
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        initJob?.cancel()
        loadJob?.cancel()
        statusJob?.cancel()
        catalogJob?.cancel()
        captionJob?.cancel()
        val p = player
        player = null
        scope.launch {
            runCatching { p?.close() }
            engineDispatcher.close()
            scope.cancel()
        }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private suspend fun ensureInit(): DynamicEnginePlayer = initMutex.withLock {
        if (player == null) {
            val created = withContext(engineDispatcher) { buildPlayer() }
            player = created
            if (hasPendingVideoSurface) created.setVideoSurface(pendingVideoSurface)
        }
        val p = checkNotNull(player)
        if (!inited) {
            statusJob?.cancel()
            catalogJob?.cancel()
            p.init(log, viewportW, viewportH)
            inited = true
            startListen(p)
        }
        _status.update {
            if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
        }
        p
    }

    private suspend fun applyCaptionConfig(p: DynamicEnginePlayer, config: PlaybackConfig) {
        val want = config.captionVisible
        val key = config.preferCaptionLanguage.first
            .takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE }
        val tracks = _captions.value.tracks
        val picked = key?.let { k ->
            tracks.firstOrNull { it.id == k || it.vssId == k || it.languageCode == k }
        } ?: tracks.firstOrNull { it.kind == "asr" } ?: tracks.firstOrNull()
        val vss = if (want) picked?.id ?: key else null
        p.setCaption(vss, log)
        syncCaptionSession(p, want)
    }

    private fun syncCaptionSession(p: DynamicEnginePlayer, visible: Boolean) {
        if (visible) {
            if (captionJob?.isActive == true) return
            captionJob = scope.launch {
                try {
                    p.collectCaptionLines({ rows ->
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

    private suspend fun refreshTracks(p: DynamicEnginePlayer) {
        val tracks = runCatching { p.captionTracks(log) }.getOrDefault(emptyList())
            .filter { it.kind != "asrt" }
        val selected = applied.preferCaptionLanguage.first
            .takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE && applied.captionVisible }
        _captions.value = CaptionState(
            tracks = tracks.map {
                CaptionTrack(it.languageCode, it.displayName, it.vssId, it.kind)
            },
            selectedLanguage = selected,
        )
        if (applied.captionVisible) applyCaptionConfig(p, applied)
    }

    private fun startListen(p: DynamicEnginePlayer) {
        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                try {
                    p.collectStatus({ applyListen(it) }, log)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Throwable) {
                }
            }
        }
        if (catalogJob?.isActive != true) {
            catalogJob = scope.launch {
                try {
                    p.collectCatalog({ applyCatalog(it) }, log)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun applyListen(st: JsonObject) {
        (st["progress"] as? JsonObject)?.let { applyProgress(it) }
        (st["state"] as? JsonObject)?.let { applyState(it) }
        (st["picture"] as? JsonObject)?.let { applyPicture(it) }
    }

    private fun applyProgress(st: JsonObject) {
        val videoId = st.str("videoId")
        val loading = loadingVideoId
        if (loading != null) {
            if (videoId != loading) return
            loadingVideoId = null
            loadedVideoId = videoId
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
        } else if (videoId != loadedVideoId) {
            return
        }
        val dur = if (st["duration"] != null) st.long("duration") else st.long("durationMs")
        val skStart = st.long("seekableStartMs")
        val skEnd = st.long("seekableEndMs")
        /* Engine already maps the DVR window onto progress/duration (0..bar). */
        val bar = if (st["progress"] != null) st.long("progress") else st.long("positionMs")
        _progress.value = Progress(
            positionMs = bar,
            durationMs = if (dur > 0L) dur else Progress.DURATION_UNKNOWN,
            bufferedPositionMs = st.long("bufferedMs"),
            seekable = st.bool("seekable", default = dur > 0L),
            isLive = st.bool("live", default = _status.value.live),
            seekableStartMs = skStart,
            seekableEndMs = if (skEnd > skStart) skEnd else if (dur > 0L) dur else 0L,
        )
    }

    private fun applyState(st: JsonObject) {
        val newState = st.str("newState") ?: return
        val pwr = st.bool("playWhenReady", default = false)
        val live = st.bool("live", default = false)
        val state = when (newState) {
            "idle" -> PlaybackState.Idle
            "buffering" -> PlaybackState.Prepared(pwr, buffering = true, ended = false)
            "ready" -> PlaybackState.Prepared(pwr, buffering = false, ended = false)
            "ended" -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            "error" -> PlaybackState.Error(
                PlayerError(
                    st.str("code") ?: "E60033",
                    st.str("message"),
                    canRetry = st.bool("canRetry", default = true),
                    needReset = st.bool("needReset", default = false),
                ),
            )
            else -> return
        }
        if (state is PlaybackState.Error) {
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
        }
        _status.update { it.copy(state = state, live = live) }
    }

    private fun applyPicture(st: JsonObject) {
        val w = (st["videoWidth"] as? JsonPrimitive)?.intOrNull ?: 0
        val h = (st["videoHeight"] as? JsonPrimitive)?.intOrNull ?: 0
        if (w <= 0 || h <= 0) return
        _status.update { it.copy(videoSize = VideoSize(w, h)) }
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
        val engineOptions = st.qualityOptions().filter {
            it.quality != VideoQualityState.AUDIO_ONLY && it.quality != "tiny"
        }
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
            it.copy(
                state = PlaybackState.Error(
                    PlayerError(code, message, canRetry = true, needReset = true),
                ),
            )
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 5_000L

        private class LoadReq(val source: PlayableItem.YtMedia, val startPositionMs: Long)

        fun create(
            client: KmpHttpClient,
            appVersion: Int,
            dispatcher: CoroutineDispatcher = Dispatchers.Main,
            logger: Logger? = null,
            cacheStore: CacheStore? = null,
            cookieJar: CookieJar = client.cookieJar,
            settings: SettingsStore? = null,
            videoSurface: MseVideoSurface,
            main: DynamicEngineManager,
            mediaProxy: LocalMediaProxy? = null,
        ): MsePlayer {
            val engineClient = if (mediaProxy != null) SabrProxyHttpClient(client, mediaProxy) else client
            val engineDispatcher = QuickJsRuntime.newDispatcher()
            return MsePlayer(dispatcher, engineDispatcher, videoSurface, logger) {
                DynamicEnginePlayer.create(
                    engineClient, appVersion, engineDispatcher, logger,
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
