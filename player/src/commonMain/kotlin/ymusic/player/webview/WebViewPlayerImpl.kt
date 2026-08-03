package ymusic.player.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import ymusic.player.AudioTrackState
import ymusic.player.CaptionState
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.PlaybackState
import ymusic.player.PlaybackStatus
import ymusic.player.PlayerError
import ymusic.player.Progress
import ymusic.player.VideoQualityState
import ymusic.player.WebViewPlayer
import kotlin.random.Random

/**
 * The platform-independent WebView YouTube engine: owns the `status`/`progress` flows, generates a
 * per-load session id, runs the unified fetch → inject → load path, and translates between the narrow
 * [ymusic.player.MediaEngine] surface and the [WebViewProtocol] bridge. All the platform-specific
 * work is behind [WebViewBridge]. Main-thread only (the engine and its bridge are driven on main; the
 * bridge delivers channel messages on main too).
 */
internal class WebViewPlayerImpl(
    private val engineJs: String,
    private val appVersion: Int,
    private val embedHtml: suspend (userAgent: String) -> String,
    bridgeFactory: (WebViewMessageSink) -> WebViewBridge,
) : WebViewPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge = bridgeFactory(WebViewMessageSink(::onMessage))

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

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    // Buffered and non-suspending: a harvest report must never block the page's message pump, and
    // dropping one when nothing is listening costs only that play's recommendations.
    private val _watchNextSeen = MutableSharedFlow<WebViewEvent.WatchNextSeen>(extraBufferCapacity = 8)
    override val watchNextSeen: SharedFlow<WebViewEvent.WatchNextSeen> = _watchNextSeen.asSharedFlow()

    private var sessionId: String = ""
    private var connected = false
    private val pending = mutableListOf<String>() // command JSONs queued until the page connects
    private var released = false

    // videoId → awaiting caller, keyed on videoId (the page's only correlation id). Main-thread only,
    // so a plain map needs no synchronization. Latest request per videoId wins (prior one cancelled).
    private val playerResWaiters = mutableMapOf<String, CompletableDeferred<String>>()
    private val poTokenWaiters = mutableMapOf<String, CompletableDeferred<String>>()

    private data class PendingLoad(
        val source: PlayableItem.YtVideoId,
        val startPositionMs: Long,
        val playWhenReady: Boolean,
        val config: PlaybackConfig,
    )

    private var pendingLoad: PendingLoad? = null
    // Set by refreshOnNext() when the cookie identity changed (sign-in/out): the next load re-fetches
    // /embed so the page runs under the new identity. The current song keeps playing on the old page.
    private var pendingRefresh = false

    /** The last-applied config; [setConfig] diffs against it so only changed fields hit the page. */
    private var appliedConfig = PlaybackConfig()

    override fun refreshOnNext() {
        if (released) return
        pendingRefresh = true
    }

    override fun load(source: PlayableItem.YtVideoId, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val request = PendingLoad(source, startPositionMs, playWhenReady, config)
        if (pendingRefresh) {
            pendingRefresh = false
            // A live page must be torn down and re-fetched; an uninitialized one inits fresh below.
            if (_status.value.state !is PlaybackState.Uninitialized) {
                pendingLoad = request
                initializePage()
                return
            }
        }
        when (val state = _status.value.state) {
            is PlaybackState.Uninitialized -> {
                pendingLoad = request
                if (!state.isInitializing) initializePage()
                return
            }
            is PlaybackState.Error -> {
                if (!state.error.canRetry) return
                if (state.error.needReset) {
                    pendingLoad = request
                    initializePage()
                    return
                }
            }
            PlaybackState.Idle, is PlaybackState.Prepared -> Unit
        }
        sendLoad(request)
    }

    private fun initializePage() {
        sessionId = (Random.nextLong() and Long.MAX_VALUE).toString()
        connected = false
        pending.clear()
        bridge.setVisible(false)
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = true), videoSize = null) }
        val boot = bootScript(sessionId)
        scope.launch {
            val html = runCatching { embedHtml(bridge.userAgent) }.getOrElse {
                // The page never came up — the legacy WV_LOAD_ERROR shape: retryable, dead session.
                fail("E60031", "failed to fetch the YouTube embed page: ${it.message}", needReset = true)
                return@launch
            }
            if (!html.contains(INJECT_PLACEHOLDER)) {
                fail("E60031", "embed page has no $INJECT_PLACEHOLDER slot", needReset = true)
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!released) bridge.load(html.replaceFirst(INJECT_PLACEHOLDER, boot), EMBED_URL)
            }
        }
    }

    private fun sendLoad(request: PendingLoad) {
        appliedConfig = request.config
        _progress.value = Progress(positionMs = request.startPositionMs)
        command(
            WebViewProtocol.encodeLoad(
                request.source.videoId,
                request.startPositionMs,
                request.playWhenReady,
                request.config.volume,
                request.config.speed,
                request.config.audioOnly,
            ),
        )
    }

    override fun play() = command(WebViewProtocol.encodePlay())
    override fun pause() = command(WebViewProtocol.encodePause())

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _progress.update { it.copy(positionMs = clamped) }
        command(WebViewProtocol.encodeSeek(clamped))
    }

    override fun stop() {
        when (_status.value.state) {
            is PlaybackState.Uninitialized -> Unit
            is PlaybackState.Error -> initializePage()
            PlaybackState.Idle, is PlaybackState.Prepared -> command(WebViewProtocol.encodeStop())
        }
        _progress.value = Progress()
        bridge.setVisible(false)
    }

    override fun setConfig(config: PlaybackConfig) {
        if (released) return
        val prev = appliedConfig
        appliedConfig = config

        if (config.volume != prev.volume) {
            val v = config.volume.coerceIn(0f, 1f)
            _status.update { it.copy(volume = v) }
            command(WebViewProtocol.encodeSetVolume(v))
        }
        if (config.speed != prev.speed) {
            val s = config.speed.coerceAtLeast(0.01f)
            _status.update { it.copy(speed = s) }
            command(WebViewProtocol.encodeSetSpeed(s))
        }
        val quality = config.preferVideoQuality.first
        if (quality != prev.preferVideoQuality.first) {
            if (config.audioOnly) {
                // Disable the video track only — the surface stays realized (see load()).
                _status.update { it.copy(videoSize = null) }
                command(WebViewProtocol.encodeSetAudioOnly(true))
            } else {
                // A concrete quality re-enables video (audio-only off) then selects the tier.
                command(WebViewProtocol.encodeSetAudioOnly(false))
                command(WebViewProtocol.encodeSelectVideoQuality(quality))
            }
        }
        if (config.preferCaptionLanguage.first != prev.preferCaptionLanguage.first) {
            val lang = config.preferCaptionLanguage.first
            command(WebViewProtocol.encodeSelectCaption(lang.takeIf { it != PlaybackConfig.UNDETERMINED_LANGUAGE }))
        }
        if (config.captionVisible != prev.captionVisible) {
            command(WebViewProtocol.encodeSetCaptionVisible(config.captionVisible))
        }
        // pitchFactor / loop / scaleMode: no wire encoder yet — ignored per the setConfig contract.
    }

    override fun selectAudioTrack(id: String) = command(WebViewProtocol.encodeSelectAudioTrack(id))

    override suspend fun requestPlayerResponse(videoId: String): String =
        requestExtraction(videoId, playerResWaiters, PLAYER_RES_TIMEOUT_MS) {
            WebViewProtocol.encodeRequestPlayerResponse(videoId)
        }

    override suspend fun requestPoToken(videoId: String): String =
        requestExtraction(videoId, poTokenWaiters, PO_TOKEN_TIMEOUT_MS) {
            WebViewProtocol.encodeRequestPoToken(videoId, PO_TOKEN_TIMEOUT_MS)
        }

    /** Shared request/await: register a per-videoId deferred, send the command, await with timeout. */
    private suspend fun requestExtraction(
        videoId: String,
        waiters: MutableMap<String, CompletableDeferred<String>>,
        timeoutMs: Long,
        encode: () -> String,
    ): String {
        check(!released) { "player released" }
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            waiters.remove(videoId)?.cancel()
            waiters[videoId] = deferred
            command(encode())
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            withContext(Dispatchers.Main) { if (waiters[videoId] === deferred) waiters.remove(videoId) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        (playerResWaiters.values + poTokenWaiters.values).forEach { it.cancel() }
        playerResWaiters.clear()
        poTokenWaiters.clear()
        scope.cancel()
        bridge.release()
        _status.update { it.copy(state = PlaybackState.Uninitialized(isInitializing = false)) }
    }

    /** Send a `ymusicAction` command, or queue it until the page connects. Main thread. */
    private fun command(commandJson: String) {
        if (released) return
        if (connected) {
            bridge.evaluate("window.ymusicAction(${JsonPrimitive(commandJson)})")
        } else {
            pending.add(commandJson)
        }
    }

    /** Bridge → engine, on the main thread. */
    private fun onMessage(channel: String, payload: String) {
        if (released) return
        when (val e = WebViewProtocol.decodeEvent(channel, payload, sessionId)) {
            WebViewEvent.Connected -> {
                connected = true
                _status.update { it.copy(state = PlaybackState.Idle) }
                val queued = pending.toList()
                pending.clear()
                queued.forEach { bridge.evaluate("window.ymusicAction(${JsonPrimitive(it)})") }
                pendingLoad?.also {
                    pendingLoad = null
                    sendLoad(it)
                }
            }
            is WebViewEvent.StateChanged -> _status.update {
                val state = when (e.state) {
                    WebPlaybackState.Idle -> PlaybackState.Idle
                    WebPlaybackState.Buffering -> PlaybackState.Prepared(e.playWhenReady, buffering = true, ended = false)
                    WebPlaybackState.Ready -> PlaybackState.Prepared(e.playWhenReady, buffering = false, ended = false)
                    WebPlaybackState.Ended -> PlaybackState.Prepared(playWhenReady = false, buffering = false, ended = true)
                    WebPlaybackState.Error -> PlaybackState.Error(
                        PlayerError(
                            e.errorCode ?: "E60033", e.errorMessage,
                            canRetry = e.errorCanRetry, needReset = e.errorNeedReset,
                        ),
                    )
                }
                bridge.setVisible(state is PlaybackState.Prepared || state is PlaybackState.Error && state.error.code != "E60031")
                it.copy(state = state)
            }
            is WebViewEvent.Progress -> _progress.update {
                it.copy(
                    positionMs = e.positionMs,
                    durationMs = if (e.durationMs > 0L) e.durationMs else Progress.DURATION_UNKNOWN,
                    bufferedPositionMs = e.bufferedMs,
                )
            }
            is WebViewEvent.QualityChanged -> _videoQuality.value = e.state
            is WebViewEvent.CaptionsChanged -> _captions.value = e.state
            is WebViewEvent.AudioTracksChanged -> _audioTracks.value = e.state
            is WebViewEvent.WatchNextSeen -> _watchNextSeen.tryEmit(e)
            is WebViewEvent.PlayerResponse -> playerResWaiters.remove(e.videoId)?.complete(e.dataJson)
            is WebViewEvent.PoToken -> poTokenWaiters.remove(e.videoId)?.let { waiter ->
                if (e.token != null) waiter.complete(e.token)
                else waiter.completeExceptionally(IllegalStateException("po token failed: ${e.errorMessage}"))
            }
            null -> Unit
        }
    }

    private fun fail(code: String, message: String, needReset: Boolean = false) = _status.update {
        bridge.setVisible(false)
        it.copy(state = PlaybackState.Error(PlayerError(code, message, needReset = needReset)))
    }

    /**
     * The first `<head>` script: the bridge's transport shim (if any), then the globals the bundle
     * reads, then the bundle itself.
     */
    private fun bootScript(sessionId: String): String = buildString {
        bridge.bootPrelude.takeIf { it.isNotEmpty() }?.let { append(it).append(";\n") }
        append("window.ymusicSessionId=").append(JsonPrimitive(sessionId)).append(";")
        append("window.mainAppVer=").append(appVersion).append(";")
        append("window.ymixInitConfig={\"platform\":").append(JsonPrimitive(bridge.platform)).append("};")
        // Globals the bundle reads (some as BARE identifiers — e.g. enablePlayerCache in skip_ads.ts —
        // so leaving them unset is an uncaught ReferenceError that surfaces as YouTube's error screen).
        append("window.isAndroid=").append(bridge.platform == "android").append(";")
        // Enabled only when the bridge intercepts /ymusicForward (has a player cache wired). webview-js
        // additionally gates the rewrite by platform (Android osVer≥24 / desktop; iOS uses a channel).
        append("window.enablePlayerCache=").append(bridge.playerCacheEnabled).append(";")
        append("\n").append(engineJs)
        // Name the injected bundle so DevTools shows it as a real, breakpointable source instead of an
        // anonymous inline script. Harmless in release — DevTools-only, no runtime cost.
        append("\n//# sourceURL=webview_ymusic.js")
    }

    companion object {
        const val EMBED_URL = "https://www.youtube.com/embed"

        /** The boot-script slot in the engine-served embed HTML (engine-js `logic/yt/embed.ts`). */
        const val INJECT_PLACEHOLDER = "INJECT_JS_HERE"

        private const val PLAYER_RES_TIMEOUT_MS = 20_000L
        private const val PO_TOKEN_TIMEOUT_MS = 30_000L
    }
}
