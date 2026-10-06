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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * YouTube [YtEngine] over engine-js + C MSE, mirroring legacy `WebViewYtPlayer`. Each [init]/[reset]
 * starts a [Session] (the legacy embed page): its own [newEngineThread] (single-threaded), one
 * [DynamicEnginePlayer] built on it, `player/init`, then a FIFO command queue. Control is
 * main-thread; commands run in call order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsePlayer internal constructor(
    private val newEngineThread: () -> CloseableCoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    private val log: Logger?,
    private val buildPlayer: (engineThread: CoroutineDispatcher) -> DynamicEnginePlayer,
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

        /** What this session has applied (JS + native audio); null until the first push, which sends everything. */
        var config: PlaybackConfig? = null

        /** Loop as last sent to JS: `config.loop` forced off while a live stream is loaded. */
        var loop: Boolean? = null
        var live = false
    }

    private var session: Session? = null

    /** The last torn-down session; the next one waits for it so two engines never overlap. */
    private var closing: Job? = null
    private var released = false

    /** Latest config from the caller. */
    private var config = PlaybackConfig()

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
        val activeSession = session ?: return
        this.config = config
        val previous = activeSession.config
        val loop = config.loop && !source.live
        activeSession.config = config
        activeSession.loop = loop
        activeSession.live = source.live
        loadingVideoId = source.videoId
        _progress.value = Progress(positionMs = startPositionMs)
        applySurfaceAudio(previous, config)
        val width = viewportW
        val height = viewportH
        send { enginePlayer ->
            try {
                enginePlayer.load(
                    source.videoId,
                    log,
                    quality = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY },
                    width = width,
                    height = height,
                    audioOnly = config.audioOnly,
                    startPositionMs = startPositionMs,
                    playWhenReady = playWhenReady,
                    speed = config.speed.coerceIn(0.25f, 3f),
                    pitch = config.pitchFactor.coerceIn(0.5f, 2f),
                    loop = loop,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                loadingVideoId = null
                fail(error, "mse load failed", needReset = true)
                return@send
            }
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
        if (!_status.value.state.readyToLoad) return
        val activeSession = session ?: return
        val previous = activeSession.config
        if (previous == config) return
        val previousLoop = activeSession.loop
        val loop = config.loop && !activeSession.live
        activeSession.config = config
        activeSession.loop = loop
        applySurfaceAudio(previous, config)
        send { enginePlayer ->
            val previousQuality = previous?.preferVideoQuality?.first?.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            val nextQuality = config.preferVideoQuality.first.takeIf { it != VideoQualityState.AUDIO_ONLY }
                ?: VideoQualityState.AUTO
            if (previous == null || config.audioOnly != previous.audioOnly || nextQuality != previousQuality) {
                enginePlayer.setQuality(nextQuality, config.audioOnly, log)
            }
            if (loop != previousLoop) enginePlayer.setLoop(loop, log)
            if (previous == null || config.speed != previous.speed || config.pitchFactor != previous.pitchFactor) {
                enginePlayer.setSpeedPitch(config.speed.coerceIn(0.25f, 3f), config.pitchFactor.coerceIn(0.5f, 2f), log)
            }
            if (previous == null ||
                config.captionVisible != previous.captionVisible ||
                config.preferCaptionLanguage != previous.preferCaptionLanguage
            ) {
                applyCaptionConfig(enginePlayer, config)
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
        var holeWidth = width.coerceAtLeast(1)
        var holeHeight = height.coerceAtLeast(1)
        if (holeHeight < 360) {
            holeWidth = (holeWidth * 360.0 / holeHeight).toInt().coerceAtLeast(1)
            holeHeight = 360
        }
        if (holeWidth == viewportW && holeHeight == viewportH) return
        viewportW = holeWidth
        viewportH = holeHeight
        if (!_status.value.state.readyToLoad) return
        send { it.setViewport(holeWidth, holeHeight, log) }
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
        val lastClose = closing
        mainScope.launch {
            lastClose?.join()
            mainScope.cancel()
        }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    private fun startSession() {
        check(session == null) { "mse session already running" }
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true), videoSize = null) }
        val newSession = Session(SupervisorJob(mainScope.coroutineContext[Job]))
        session = newSession
        val previousClose = closing
        newSession.scope.launch {
            // NonCancellable so a reset-during-reset still chains behind the older close.
            withContext(NonCancellable) { previousClose?.join() }
            ensureActive()
            val engineThread = newEngineThread()
            var enginePlayer: DynamicEnginePlayer? = null
            try {
                // Boot is a blocking eval on the engine thread; a built engine must reach the finally.
                enginePlayer = withContext(engineThread + NonCancellable) { buildPlayer(engineThread) }
                newSession.player = enginePlayer
                if (hasPendingVideoSurface) enginePlayer.setVideoSurface(pendingVideoSurface)
                enginePlayer.init(log, viewportW, viewportH)
                listen(newSession, enginePlayer)
                _status.update {
                    if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
                }
                for (command in newSession.commands) {
                    try {
                        command(enginePlayer)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log?.log(Logger.Level.WARN, "mse command failed", error)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (session === newSession) fail(error, "mse init failed", needReset = true)
            } finally {
                withContext(NonCancellable) {
                    if (enginePlayer != null) closePlayerBounded(enginePlayer)
                    closeEngineThread(engineThread)
                }
            }
        }
    }

    /**
     * A wedged engine thread never runs its close hop, so the wait is bounded and the successor
     * boots anyway. Launched [Dispatchers.Main.immediate] so the close's engine-thread hop is
     * already queued — ahead of [closeEngineThread] — by the time this suspends.
     */
    private suspend fun closePlayerBounded(enginePlayer: DynamicEnginePlayer) {
        val closeJob = mainScope.launch(NonCancellable) {
            try {
                enginePlayer.close()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse close failed", error)
            }
        }
        if (withTimeoutOrNull(ENGINE_CLOSE_TIMEOUT_MS) { closeJob.join() } == null) {
            log?.log(Logger.Level.WARN, "mse engine close timed out; abandoning it")
        }
    }

    /** Queued work still drains, then the thread exits. Off main and not awaited: native `close`
     *  blocks until the worker terminates, which a wedged engine thread never does. */
    private fun closeEngineThread(engineThread: CloseableCoroutineDispatcher) {
        mainScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                engineThread.close()
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse engine thread close failed", error)
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
        val oldSession = session ?: return
        session = null
        oldSession.commands.close()
        oldSession.job.cancel()
        closing = oldSession.job
    }

    private fun send(command: suspend (DynamicEnginePlayer) -> Unit) {
        session?.commands?.trySend(command)
    }

    private suspend fun readyPlayer(): DynamicEnginePlayer = withContext(Dispatchers.Main.immediate) {
        if (released) throw TechnicalException("E60034", "mse engine released")
        init()
        val state = _status.first { it.state.readyToLoad || it.state is PlaybackState.Error }.state
        val enginePlayer = session?.player
        if (!state.readyToLoad || enginePlayer == null) {
            throw TechnicalException("E60034", "mse engine not ready: $state")
        }
        enginePlayer
    }

    /** Native host audio; the host is per session, so [previous] is that session's last push. */
    private fun applySurfaceAudio(previous: PlaybackConfig?, config: PlaybackConfig) {
        if (previous == null || config.volume != previous.volume) {
            videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        }
        if (previous == null ||
            config.eqBands != previous.eqBands ||
            config.bassBoostDb != previous.bassBoostDb ||
            config.virtualizer != previous.virtualizer
        ) {
            videoSurface.setAudioEffect(
                FloatArray(10) { index -> config.eqBands.getOrElse(index) { 0f } },
                config.bassBoostDb.coerceIn(0f, 12f),
                config.virtualizer.coerceIn(0f, 2f),
            )
        }
    }

    private suspend fun applyCaptionConfig(enginePlayer: DynamicEnginePlayer, config: PlaybackConfig) {
        val preferred = config.preferCaptionLanguage.first.takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE }
        enginePlayer.setCaption(preferred, log, visible = config.captionVisible)
        syncCaptionSession(enginePlayer, config.captionVisible)
    }

    private fun syncCaptionSession(enginePlayer: DynamicEnginePlayer, visible: Boolean) {
        val activeSession = session ?: return
        if (!visible) {
            activeSession.captionJob?.cancel()
            activeSession.captionJob = null
            _captionLines.value = emptyList()
            return
        }
        if (activeSession.captionJob?.isActive == true) return
        activeSession.captionJob = activeSession.scope.launch {
            try {
                enginePlayer.collectCaptionLines({ rows ->
                    _captionLines.value = rows.map { CaptionLine(it.id, it.text) }
                }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse caption stream failed", error)
                _captionLines.value = emptyList()
            }
        }
    }

    private fun listen(activeSession: Session, enginePlayer: DynamicEnginePlayer) {
        activeSession.scope.launch {
            try {
                enginePlayer.collectStatus({ applyListen(it) }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (session === activeSession) fail(error, "mse status stream failed", needReset = true)
            }
        }
        activeSession.scope.launch {
            try {
                enginePlayer.collectCatalog({ applyCatalog(it) }, log)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log?.log(Logger.Level.WARN, "mse catalog stream failed", error)
            }
        }
    }

    private fun applyListen(json: JsonObject) {
        (json["progress"] as? JsonObject)?.let { applyProgress(it) }
        (json["state"] as? JsonObject)?.let { applyState(it) }
        (json["picture"] as? JsonObject)?.let { applyPicture(it) }
    }

    private fun applyProgress(json: JsonObject) {
        val videoId = json.str("videoId")
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
        val durationMs = if (json["duration"] != null) json.long("duration") else json.long("durationMs")
        val seekableStartMs = json.long("seekableStartMs")
        val seekableEndMs = json.long("seekableEndMs")
        /* Engine already maps the DVR window onto progress/duration (0..positionMs). */
        val positionMs = if (json["progress"] != null) json.long("progress") else json.long("positionMs")
        _progress.value = Progress(
            positionMs = positionMs,
            durationMs = if (durationMs > 0L) durationMs else Progress.DURATION_UNKNOWN,
            bufferedPositionMs = json.long("bufferedMs"),
            seekable = json.bool("seekable", default = durationMs > 0L),
            isLive = json.bool("live", default = _status.value.live),
            seekableStartMs = seekableStartMs,
            seekableEndMs = if (seekableEndMs > seekableStartMs) seekableEndMs else if (durationMs > 0L) durationMs else 0L,
        )
    }

    private fun applyState(json: JsonObject) {
        val newState = json.str("newState") ?: return
        val playWhenReady = json.bool("playWhenReady", default = false)
        val live = json.bool("live", default = false)
        val state = when (newState) {
            "idle" -> PlaybackState.Idle
            "buffering" -> PlaybackState.Prepared(playWhenReady, buffering = true, ended = false)
            "ready" -> PlaybackState.Prepared(playWhenReady, buffering = false, ended = false)
            "ended" -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            "error" -> PlaybackState.Error(
                PlayerError(
                    json.str("code") ?: "E60033",
                    json.str("message"),
                    canRetry = json.bool("canRetry", default = true),
                    needReset = json.bool("needReset", default = false),
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

    private fun applyPicture(json: JsonObject) {
        val videoWidth = (json["videoWidth"] as? JsonPrimitive)?.intOrNull ?: 0
        val videoHeight = (json["videoHeight"] as? JsonPrimitive)?.intOrNull ?: 0
        if (videoWidth <= 0 || videoHeight <= 0) return
        _status.update { it.copy(videoSize = VideoSize(videoWidth, videoHeight)) }
    }

    private fun applyCatalog(json: JsonObject) {
        val audioOnly = json.bool("audioOnly", default = false)
        val actual = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.str("quality") ?: VideoQualityState.AUTO
        }
        val prefer = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.str("preferQuality") ?: config.preferVideoQuality.first
        }
        val engineOptions = json.qualityOptions().filter {
            it.quality != VideoQualityState.AUDIO_ONLY && it.quality != "tiny"
        }
        val options = listOf(VideoQualityOption(VideoQualityState.AUDIO_ONLY, "Audio-only")) +
            engineOptions.ifEmpty { listOf(VideoQualityOption(VideoQualityState.AUTO, "Auto")) }
        _videoQuality.value = VideoQualityState(
            options = options,
            actualQuality = actual,
            preferredQuality = prefer,
        )
        _captions.value = CaptionState(tracks = json.captionTracks(), selectedLanguage = json.str("caption"))
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
        private const val ENGINE_CLOSE_TIMEOUT_MS = 3_000L

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
            return MsePlayer({ QuickJsRuntime.newDispatcher() }, videoSurface, logger) { engineThread ->
                DynamicEnginePlayer.create(
                    engineClient, appVersion, engineThread, logger,
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
    val primitive = this[key] as? JsonPrimitive ?: return default
    return primitive.booleanOrNull ?: (primitive.content == "true")
}

private fun JsonObject.captionTracks(): List<CaptionTrack> {
    val rows = this["captionTracks"] as? JsonArray ?: return emptyList()
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val vssId = row.str("vssId") ?: return@mapNotNull null
        CaptionTrack(
            languageCode = row.str("languageCode").orEmpty(),
            displayName = row.str("displayName") ?: vssId,
            vssId = vssId,
            kind = row.str("kind").orEmpty(),
        )
    }
}

private fun JsonObject.qualityOptions(): List<VideoQualityOption> {
    val qualities = this["qualities"] as? JsonArray ?: return emptyList()
    return qualities.mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val quality = (option["quality"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        VideoQualityOption(quality, (option["label"] as? JsonPrimitive)?.contentOrNull ?: quality)
    }
}
