package io.ymusic.player.mse

import io.ymusic.dynamicengine.BundleSignatureException
import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.JsSourceProvider
import io.ymusic.dynamicengine.embeddedPlayerJsSource
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.AudioTrackState
import io.ymusic.player.CaptionLine
import io.ymusic.player.CaptionState
import io.ymusic.player.CaptionTrack
import io.ymusic.player.MediaDetails
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
import kmpcommon.log.Logger
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.serialization.json.JsonObject
import projectcommon.error.TechnicalException
import projectcommon.error.errorCode
import projectcommon.json.boolOrNull
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/**
 * YouTube [YtEngine] over engine-js + C MSE, mirroring legacy `WebViewYtPlayer`. Each [init]/[reset]
 * starts a [Session] (the legacy embed page): its own [newEngineThread] (single-threaded), one
 * [DynamicEnginePlayer] built on it, `player/init`, then a FIFO command queue. Control is
 * main-thread; commands run in call order.
 *
 * player.js upgrades only between sessions: each session boots the newest verified bundle of
 * [playerJs] (cached remote, else embedded), and a newer published one is downloaded in the
 * background for the NEXT session — a running player is never swapped under playback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsePlayer internal constructor(
    private val newEngineThread: () -> CloseableCoroutineDispatcher,
    val videoSurface: MseVideoSurface,
    private val log: Logger?,
    private val playerJs: JsSourceProvider,
    private val buildPlayer: (engineThread: CoroutineDispatcher, playerJs: String) -> DynamicEnginePlayer,
) : YtEngine {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Fire-and-forget teardown of abandoned sessions; deliberately outlives [release]. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private var freshConfigNext = false

    /** The next session takes its player config from a new homepage scrape ([PlayerImpl] resets). */
    override fun refreshOnNext() {
        freshConfigNext = true
    }

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
        _status.update { it.copy(details = null) }
        applySurfaceAudio(previous, config)
        val width = viewportW
        val height = viewportH
        if (captionChanged(previous, config)) send { applyCaptionConfig(it, config) }
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
        _status.update { it.copy(state = PlaybackState.Idle, videoSize = null, details = null) }
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
            if (captionChanged(previous, config)) applyCaptionConfig(enginePlayer, config)
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
        if (!hostLive() || out.isEmpty()) 0 else videoSurface.pcmLevels(out)

    override fun presentedFrames(): Int =
        if (!hostLive()) 0 else videoSurface.presentedFrames()

    /** A closing session frees its native host off main, only after main has cleared its player. */
    private fun hostLive(): Boolean = !released && session?.player != null

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

    /** Downloads a newer signed player.js for the next session; at most one check per interval. */
    private suspend fun prefetchPlayerJs(runningVersion: Long) {
        try {
            if (now() - playerJs.lastFetch() < PLAYER_JS_CHECK_INTERVAL_MS) return
            val fetched = playerJs.fetchIfAny(runningVersion) { _, version -> version }
            if (fetched != null) log?.log(Logger.Level.INFO, "player.js v$fetched staged for the next session")
        } catch (error: CancellationException) {
            throw error
        } catch (error: BundleSignatureException) {
            log?.captureError(error, "player.js update rejected")
        } catch (error: Throwable) {
            log?.log(Logger.Level.WARN, "player.js update check failed", error)
        }
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
                val bundle = playerJs.getCached()
                val (source, version) = bundle ?: playerJs.getEmbed()
                // Boot is a blocking eval on the engine thread; a built engine must reach the finally.
                enginePlayer = try {
                    withContext(engineThread + NonCancellable) { buildPlayer(engineThread, source) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (bundle == null) throw error
                    // A signed remote bundle that will not boot must not take playback down with it.
                    log?.captureError(error, "player.js v$version failed to boot, using the embedded one")
                    withContext(engineThread + NonCancellable) { buildPlayer(engineThread, playerJs.getEmbed().first) }
                }
                newSession.player = enginePlayer
                if (hasPendingVideoSurface) enginePlayer.setVideoSurface(pendingVideoSurface)
                val freshConfig = freshConfigNext
                freshConfigNext = false
                enginePlayer.init(log, viewportW, viewportH, freshConfig)
                listen(newSession, enginePlayer)
                _status.update {
                    if (it.state is PlaybackState.Uninitialized) it.copy(state = PlaybackState.Idle) else it
                }
                cleanupScope.launch { prefetchPlayerJs(version) }
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
                newSession.player = null
                withContext(Dispatchers.Default + NonCancellable) {
                    if (enginePlayer != null) closePlayerBounded(enginePlayer)
                    closeEngineThread(engineThread)
                }
            }
        }
    }

    /**
     * A wedged engine thread never runs its close hop, so the wait is bounded and the successor
     * boots anyway. [CoroutineStart.UNDISPATCHED] so the close's engine-thread hop is already
     * queued — ahead of [closeEngineThread] — by the time this suspends.
     */
    private suspend fun closePlayerBounded(enginePlayer: DynamicEnginePlayer) {
        val closeJob = cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        cleanupScope.launch(Dispatchers.IO) {
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
        if (!hostLive()) return
        if (previous == null || config.volume != previous.volume) {
            videoSurface.setVolume(config.volume.coerceIn(0f, 1f))
        }
        if (previous == null ||
            config.eqBands != previous.eqBands ||
            config.bassBoostDb != previous.bassBoostDb ||
            config.virtualizer != previous.virtualizer ||
            config.preampDb != previous.preampDb
        ) {
            videoSurface.setAudioEffect(
                FloatArray(10) { index -> config.eqBands.getOrElse(index) { 0f } },
                config.bassBoostDb.coerceIn(-12f, 12f),
                config.virtualizer.coerceIn(0f, 2f),
                config.preampDb.coerceIn(-12f, 12f),
            )
        }
    }

    private fun captionChanged(previous: PlaybackConfig?, config: PlaybackConfig): Boolean =
        previous == null ||
            config.captionVisible != previous.captionVisible ||
            config.preferCaptionLanguage != previous.preferCaptionLanguage

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
        json.objectOrNull("progress")?.let { applyProgress(it) }
        json.objectOrNull("state")?.let { applyState(it) }
        json.objectOrNull("picture")?.let { applyPicture(it) }
        json.objectOrNull("details")?.let { applyDetails(it) }
        json.objectOrNull("watchNext")?.let { applyWatchNext(it) }
    }

    private fun applyWatchNext(json: JsonObject) {
        val videoId = json.stringOrNull("videoId") ?: return
        val contents = json.stringOrNull("contents") ?: return
        _watchNextSeen.tryEmit(WatchNextSeen(videoId, contents))
    }

    private fun applyDetails(json: JsonObject) {
        val videoId = json.stringOrNull("videoId") ?: return
        if (videoId != (loadingVideoId ?: loadedVideoId)) return
        val details = MediaDetails(videoId, json.stringOrNull("channelTitle"), json.stringOrNull("channelUrl"))
        _status.update { it.copy(details = details) }
    }

    private fun applyProgress(json: JsonObject) {
        val videoId = json.stringOrNull("videoId")
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
        val durationMs = json.longOrNull("duration") ?: json.longOrNull("durationMs") ?: 0L
        val seekableStartMs = json.longOrNull("seekableStartMs") ?: 0L
        val seekableEndMs = json.longOrNull("seekableEndMs") ?: 0L
        /* Engine already maps the DVR window onto progress/duration (0..positionMs). */
        val positionMs = json.longOrNull("progress") ?: json.longOrNull("positionMs") ?: 0L
        _progress.value = Progress(
            positionMs = positionMs,
            durationMs = if (durationMs > 0L) durationMs else Progress.DURATION_UNKNOWN,
            bufferedPositionMs = json.longOrNull("bufferedMs") ?: 0L,
            seekable = json.boolOrNull("seekable") ?: (durationMs > 0L),
            isLive = json.boolOrNull("live") ?: _status.value.live,
            seekableStartMs = seekableStartMs,
            seekableEndMs = if (seekableEndMs > seekableStartMs) seekableEndMs else if (durationMs > 0L) durationMs else 0L,
        )
    }

    private fun applyState(json: JsonObject) {
        val newState = json.stringOrNull("newState") ?: return
        val playWhenReady = json.boolOrNull("playWhenReady") ?: false
        val live = json.boolOrNull("live") ?: false
        val state = when (newState) {
            "idle" -> PlaybackState.Idle
            "buffering" -> PlaybackState.Prepared(playWhenReady, buffering = true, ended = false)
            "ready" -> PlaybackState.Prepared(playWhenReady, buffering = false, ended = false)
            "ended" -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
            "error" -> PlaybackState.Error(
                PlayerError(
                    json.stringOrNull("code") ?: "E60033",
                    json.stringOrNull("message"),
                    canRetry = json.boolOrNull("canRetry") ?: true,
                    needReset = json.boolOrNull("needReset") ?: false,
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
        val videoWidth = json.intOrNull("videoWidth") ?: 0
        val videoHeight = json.intOrNull("videoHeight") ?: 0
        if (videoWidth <= 0 || videoHeight <= 0) return
        _status.update { it.copy(videoSize = VideoSize(videoWidth, videoHeight)) }
    }

    private fun applyCatalog(json: JsonObject) {
        val audioOnly = json.boolOrNull("audioOnly") ?: false
        val actual = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.stringOrNull("quality") ?: VideoQualityState.AUTO
        }
        val prefer = if (audioOnly) {
            VideoQualityState.AUDIO_ONLY
        } else {
            json.stringOrNull("preferQuality") ?: config.preferVideoQuality.first
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
        _captions.value = CaptionState(tracks = json.captionTracks(), selectedLanguage = json.stringOrNull("caption"))
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
                    PlayerError(code, message, canRetry = true, needReset = needReset, cause = error),
                ),
            )
        }
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 5_000L
        private const val PLAYER_JS_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
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
            playerJs: JsSourceProvider = embeddedPlayerJsSource(),
            /** The running player.js revision, reported each time a player session's bundle starts. */
            onPlayerJsVersion: ((Int) -> Unit)? = null,
        ): MsePlayer {
            val engineClient = SabrProxyHttpClient(client, mediaProxy)
            return MsePlayer({ QuickJsRuntime.newDispatcher() }, videoSurface, logger, playerJs) { engineThread, source ->
                DynamicEnginePlayer.create(
                    engineClient, appVersion, engineThread, logger,
                    engineJs = source,
                    cacheStore = cacheStore,
                    cookieJar = cookieJar,
                    settings = settings,
                    videoSurface = videoSurface,
                    main = main,
                    onBackendVersion = onPlayerJsVersion,
                )
            }
        }
    }
}

private fun JsonObject.captionTracks(): List<CaptionTrack> {
    return objects("captionTracks").mapNotNull { row ->
        val vssId = row.stringOrNull("vssId") ?: return@mapNotNull null
        CaptionTrack(
            languageCode = row.stringOrNull("languageCode").orEmpty(),
            displayName = row.stringOrNull("displayName") ?: vssId,
            vssId = vssId,
            kind = row.stringOrNull("kind").orEmpty(),
        )
    }
}

private fun JsonObject.qualityOptions(): List<VideoQualityOption> {
    return objects("qualities").mapNotNull { option ->
        val quality = option.stringOrNull("quality") ?: return@mapNotNull null
        VideoQualityOption(quality, option.stringOrNull("label") ?: quality)
    }
}
