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
import kmpcommon.error.TechnicalException
import kmpcommon.error.errorCode
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * YouTube [YtEngine] over engine-js + C MSE, mirroring legacy `WebViewYtPlayer`. Each [init]/[reset]
 * starts a [Session] (the legacy embed page): one [DynamicEnginePlayer] built on [engineDispatcher],
 * `player/init`, then a FIFO command queue. Control is main-thread; commands run in call order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsePlayer internal constructor(
    private val engineDispatcher: CloseableCoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    private val log: Logger?,
    private val buildPlayer: () -> DynamicEnginePlayer,
) : YtEngine {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    private class Session(val job: Job) {
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)
        val commands = Channel<suspend (DynamicEnginePlayer) -> Unit>(Channel.UNLIMITED)
        var player: DynamicEnginePlayer? = null
        var captionJob: Job? = null
    }

    private var session: Session? = null

    /** The last torn-down session; the next one waits for it so two engines never overlap. */
    private var closing: Job? = null
    private var released = false

    /** Latest config from the caller. */
    private var config = PlaybackConfig()

    /** What the current session's JS has applied; [setConfig] diffs against it. */
    private var sessionConfig = PlaybackConfig()
    private var loadingVideoId: String? = null
    private var loadedVideoId: String? = null
    private var loadTimeoutJob: Job? = null
    private var pendingVideoSurface: MseVideoSurface? = null
    private var hasPendingVideoSurface = false
    private var viewportW: Int = 640
    private var viewportH: Int = 360

    /** [io.ymusic.player.PlayerImpl] owns reset-on-next and calls [reset] before the next load. */
    override fun refreshOnNext() = Unit

    override fun init() {
        requireMainThread()
        if (released || session != null) return
        startSession()
    }

    override fun reset() {
        requireMainThread()
        if (released) return
        tearDownSession()
        startSession()
    }

    override fun load(
        source: PlayableItem.YtMedia,
        startPositionMs: Long,
        playWhenReady: Boolean,
        config: PlaybackConfig,
    ) {
        requireMainThread()
        if (released) return
        if (!_status.value.state.readyToLoad) {
            log?.log(Logger.Level.WARN, "mse load(${source.videoId}) while ${_status.value.state}")
            return
        }
        this.config = config
        sessionConfig = config
        loadingVideoId = source.videoId
        _progress.value = Progress(positionMs = startPositionMs)
        applySurfaceAudio(config)
        val w = viewportW
        val h = viewportH
        send { p ->
            try {
                p.load(
                    source.videoId,
                    log,
                    quality = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY },
                    width = w,
                    height = h,
                    audioOnly = config.audioOnly,
                    startPositionMs = startPositionMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loadingVideoId = null
                loadTimeoutJob?.cancel()
                loadTimeoutJob = null
                fail(e, "mse load failed", needReset = false)
                return@send
            }
            p.setLoop(config.loop && !source.live, log)
            if (playWhenReady) p.play(log) else p.pause(log)
            p.setSpeedPitch(config.speed.coerceIn(0.25f, 3f), config.pitchFactor.coerceIn(0.5f, 2f), log)
            refreshTracks(p)
            applyCaptionConfig(p, config)
        }
        loadTimeoutJob?.cancel()
        loadTimeoutJob = mainScope.launch {
            delay(LOAD_TIMEOUT_MS)
            loadTimeoutJob = null
            tearDownSession()
            fail(TechnicalException("E60051", "load timeout ${source.videoId}"), "load timeout", needReset = true)
        }
    }

    override fun preload(source: PlayableItem.YtMedia?) {
        requireMainThread()
        source ?: return
        if (released || !_status.value.state.readyToLoad) return
        send { it.preload(source.videoId, log) }
    }

    override fun play() {
        requireMainThread()
        if (released || !_status.value.state.readyToLoad) return
        send { it.play(log) }
    }

    override fun pause() {
        requireMainThread()
        if (released || !_status.value.state.readyToLoad) return
        send { it.pause(log) }
    }

    override fun seekTo(positionMs: Long) {
        requireMainThread()
        if (released || _status.value.state is PlaybackState.Uninitialized) return
        send { it.seekTo(positionMs, log) }
    }

    override fun stop() {
        requireMainThread()
        if (released) return
        when (_status.value.state) {
            is PlaybackState.Uninitialized -> return
            is PlaybackState.Error -> {
                tearDownSession()
                return
            }
            else -> Unit
        }
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        send { it.stop(log) }
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null) }
        _progress.value = Progress()
    }

    override fun setConfig(config: PlaybackConfig) {
        requireMainThread()
        if (released) return
        this.config = config
        applySurfaceAudio(config)
        if (!_status.value.state.readyToLoad) return
        val prev = sessionConfig
        if (prev == config) return
        sessionConfig = config
        send { p ->
            val prevQ = prev.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            val nextQ = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            if (config.audioOnly != prev.audioOnly || nextQ != prevQ) {
                p.setQuality(nextQ, config.audioOnly, log)
            }
            if (config.loop != prev.loop) p.setLoop(config.loop, log)
            if (config.speed != prev.speed || config.pitchFactor != prev.pitchFactor) {
                p.setSpeedPitch(config.speed.coerceIn(0.25f, 3f), config.pitchFactor.coerceIn(0.5f, 2f), log)
            }
            if (config.captionVisible != prev.captionVisible ||
                config.preferCaptionLanguage != prev.preferCaptionLanguage
            ) {
                applyCaptionConfig(p, config)
            }
        }
    }

    override fun selectAudioTrack(id: String) = Unit

    /** Android TextureView: pass the window when it appears, null on destroy. JVM bitmap path ignores this. */
    fun setVideoSurface(surface: MseVideoSurface?) {
        pendingVideoSurface = surface
        hasPendingVideoSurface = true
        session?.player?.setVideoSurface(surface)
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
        if (!_status.value.state.readyToLoad) return
        send { it.setViewport(w, h, log) }
    }

    override suspend fun requestPlayerResponse(videoId: String): String =
        readyPlayer().cachedPlayerResponse(videoId, log).toString()

    override suspend fun requestPoToken(videoId: String): String =
        readyPlayer().mintContentPoToken(videoId, log)

    override fun pcmLevels(out: FloatArray): Int =
        if (released || out.isEmpty()) 0 else videoSurface.pcmLevels(out)

    override fun presentedFrames(): Int =
        if (released) 0 else videoSurface.presentedFrames()

    override fun release() {
        requireMainThread()
        if (released) return
        released = true
        tearDownSession()
        val last = closing
        mainScope.launch {
            last?.join()
            engineDispatcher.close()
            mainScope.cancel()
        }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private fun startSession() {
        sessionConfig = PlaybackConfig()
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true), videoSize = null) }
        val s = Session(SupervisorJob(mainScope.coroutineContext[Job]))
        session = s
        val previous = closing
        s.scope.launch {
            // NonCancellable so a reset-during-reset still chains behind the older close.
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            var p: DynamicEnginePlayer? = null
            try {
                // Boot is a blocking eval on the engine thread; a built engine must reach the finally.
                p = withContext(engineDispatcher + NonCancellable) { buildPlayer() }
                s.player = p
                if (hasPendingVideoSurface) p.setVideoSurface(pendingVideoSurface)
                p.init(log, viewportW, viewportH)
                listen(s, p)
                _status.update {
                    if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
                }
                for (cmd in s.commands) {
                    try {
                        cmd(p)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log?.log(Logger.Level.WARN, "mse command failed", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (session === s) fail(e, "mse init failed", needReset = true)
            } finally {
                if (p != null) {
                    withContext(NonCancellable) {
                        try {
                            p.close()
                        } catch (e: Throwable) {
                            log?.log(Logger.Level.WARN, "mse close failed", e)
                        }
                    }
                }
            }
        }
    }

    /** Legacy `resetInternal(loadBlank = true)`: kill the session, leave [status] as is. */
    private fun tearDownSession() {
        loadingVideoId = null
        loadedVideoId = null
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        _captionLines.value = emptyList()
        val s = session ?: return
        session = null
        s.commands.close()
        s.job.cancel()
        closing = s.job
    }

    private fun send(cmd: suspend (DynamicEnginePlayer) -> Unit) {
        session?.commands?.trySend(cmd)
    }

    private suspend fun readyPlayer(): DynamicEnginePlayer = withContext(Dispatchers.Main.immediate) {
        if (released) throw TechnicalException("E60034", "mse engine released")
        init()
        val state = _status.first { it.state.readyToLoad || it.state is PlaybackState.Error }.state
        val p = session?.player
        if (!state.readyToLoad || p == null) {
            throw TechnicalException("E60034", "mse engine not ready: $state")
        }
        p
    }

    private fun applySurfaceAudio(config: PlaybackConfig) {
        videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        videoSurface.setAudioEffect(
            FloatArray(10) { i -> config.eqBands.getOrElse(i) { 0f } },
            config.bassBoostDb.coerceIn(0f, 12f),
            config.virtualizer.coerceIn(0f, 2f),
        )
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
        val s = session ?: return
        if (!visible) {
            s.captionJob?.cancel()
            s.captionJob = null
            _captionLines.value = emptyList()
            return
        }
        if (s.captionJob?.isActive == true) return
        s.captionJob = s.scope.launch {
            try {
                p.collectCaptionLines({ rows ->
                    _captionLines.value = rows.map { CaptionLine(it.id, it.text) }
                }, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log?.log(Logger.Level.WARN, "mse caption stream failed", e)
                _captionLines.value = emptyList()
            }
        }
    }

    private suspend fun refreshTracks(p: DynamicEnginePlayer) {
        val tracks = p.captionTracks(log).filter { it.kind != "asrt" }
        val selected = config.preferCaptionLanguage.first
            .takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE && config.captionVisible }
        _captions.value = CaptionState(
            tracks = tracks.map { CaptionTrack(it.languageCode, it.displayName, it.vssId, it.kind) },
            selectedLanguage = selected,
        )
    }

    private fun listen(s: Session, p: DynamicEnginePlayer) {
        s.scope.launch {
            try {
                p.collectStatus({ applyListen(it) }, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (session === s) fail(e, "mse status stream failed", needReset = true)
            }
        }
        s.scope.launch {
            try {
                p.collectCatalog({ applyCatalog(it) }, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log?.log(Logger.Level.WARN, "mse catalog stream failed", e)
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
            st.str("preferQuality") ?: config.preferVideoQuality.first
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

    private fun fail(error: Throwable, message: String, needReset: Boolean) {
        val code = error.errorCode()
            ?: Regex("""E\d{5}""").find(error.message.orEmpty())?.value
            ?: "E60033"
        log?.log(Logger.Level.WARN, "$code $message", error)
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        _status.update {
            it.copy(
                state = PlaybackState.Error(
                    PlayerError(code, message, canRetry = true, needReset = needReset),
                ),
            )
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 5_000L

        fun create(
            client: KmpHttpClient,
            appVersion: Int,
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
            return MsePlayer(engineDispatcher, videoSurface, logger) {
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
