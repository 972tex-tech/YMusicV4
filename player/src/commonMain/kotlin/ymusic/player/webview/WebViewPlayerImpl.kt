package ymusic.player.webview

import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.utf8String
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import ymusic.player.PlaybackConfig
import ymusic.player.PlaybackState
import ymusic.player.PlaybackStatus
import ymusic.player.PlayerError
import ymusic.player.Progress
import ymusic.player.ResolvedSource
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
    private val httpClient: KmpHttpClient,
    bridgeFactory: (WebViewMessageSink) -> WebViewBridge,
) : WebViewPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge = bridgeFactory(WebViewMessageSink(::onMessage))

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var sessionId: String = ""
    private var connected = false
    private val pending = mutableListOf<String>() // command JSONs queued until the page connects
    private var released = false

    override fun load(source: ResolvedSource.YtVideoId, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val videoId = source.videoId
        sessionId = (Random.nextLong() and Long.MAX_VALUE).toString()
        connected = false
        pending.clear()
        _status.update {
            it.copy(
                state = PlaybackState.Buffering, playWhenReady = playWhenReady, error = null, videoSize = null,
                volume = config.volume, speed = config.speed, audioOnly = config.audioOnly,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        // The Load command is the first thing sent once the page connects; config rides along in the
        // batch so volume/speed/audio-only apply before the first frame.
        pending.add(
            WebViewProtocol.encodeLoad(videoId, startPositionMs, playWhenReady, config.volume, config.speed, config.audioOnly),
        )
        val boot = bootScript(sessionId)
        scope.launch {
            val html = runCatching { fetchEmbedHtml() }.getOrElse {
                fail("E60031", "failed to fetch the YouTube embed page: ${it.message}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!released) bridge.load(HtmlInjector.inject(html, boot), EMBED_URL)
            }
        }
    }

    override fun play() = command(WebViewProtocol.encodePlay())
    override fun pause() = command(WebViewProtocol.encodePause())

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _progress.update { it.copy(positionMs = clamped) }
        command(WebViewProtocol.encodeSeek(clamped))
    }

    override fun stop() {
        command(WebViewProtocol.encodeStop())
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false, videoSize = null) }
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _status.update { it.copy(volume = v) }
        command(WebViewProtocol.encodeSetVolume(v))
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceAtLeast(0.01f)
        _status.update { it.copy(speed = s) }
        command(WebViewProtocol.encodeSetSpeed(s))
    }

    override fun setAudioOnly(audioOnly: Boolean) {
        _status.update { it.copy(audioOnly = audioOnly, videoSize = if (audioOnly) null else it.videoSize) }
        command(WebViewProtocol.encodeSetAudioOnly(audioOnly))
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        bridge.release()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false) }
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
                val queued = pending.toList()
                pending.clear()
                queued.forEach { bridge.evaluate("window.ymusicAction(${JsonPrimitive(it)})") }
            }
            is WebViewEvent.StateChanged -> _status.update {
                it.copy(
                    state = e.state,
                    playWhenReady = e.playWhenReady,
                    error = if (e.state == PlaybackState.Error) {
                        PlayerError(e.errorCode ?: "E60033", e.errorMessage)
                    } else {
                        null
                    },
                )
            }
            is WebViewEvent.Progress -> _progress.update {
                it.copy(
                    positionMs = e.positionMs,
                    durationMs = if (e.durationMs > 0L) e.durationMs else Progress.DURATION_UNKNOWN,
                    bufferedPositionMs = e.bufferedMs,
                )
            }
            null -> Unit
        }
    }

    private fun fail(code: String, message: String) = _status.update {
        it.copy(state = PlaybackState.Error, playWhenReady = false, error = PlayerError(code, message))
    }

    private suspend fun fetchEmbedHtml(): String = withContext(Dispatchers.Default) {
        val resp = httpClient.execute(RequestBuilder().url(EMBED_URL).build())
        try {
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body.utf8String()
        } finally {
            resp.close()
        }
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
        append("\n").append(engineJs)
    }

    private companion object {
        const val EMBED_URL = "https://www.youtube.com/embed"
    }
}
