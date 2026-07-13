package ymusic.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class PlayerImpl(
    private val engines: PlayerEngines,
    mainDispatcher: CoroutineDispatcher,
    random: Random,
) : Player {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val controller = QueueController(random)

    private var localEngine: LocalPlayer? = null
    private var webViewEngine: WebViewPlayer? = null

    private var playWhenReadyIntent = false
    private var released = false

    /** Monotonic stamp for the `Pair<String, Long>` preference fields — freshest choice wins a restore. */
    private var configSeq = 0L
    private fun nextSeq(): Long = ++configSeq

    /** Bumped on every load; the Ended auto-advance fires at most once per generation. */
    private var loadGeneration = 0
    private var advancedGeneration = -1

    private val engineJobs = mutableListOf<Job>()
    private var sleepJob: Job? = null

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _queue = MutableStateFlow(QueueState())
    override val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQualityState.DEFAULT)
    override val videoQuality: StateFlow<VideoQualityState> = _videoQuality.asStateFlow()

    private val _captions = MutableStateFlow(CaptionState.DEFAULT)
    override val captions: StateFlow<CaptionState> = _captions.asStateFlow()

    private val _audioTracks = MutableStateFlow(AudioTrackState.DEFAULT)
    override val audioTracks: StateFlow<AudioTrackState> = _audioTracks.asStateFlow()

    private val _config = MutableStateFlow(PlaybackConfig())
    override val config: StateFlow<PlaybackConfig> = _config.asStateFlow()
    private var currentConfig: PlaybackConfig
        get() = _config.value
        set(value) { _config.value = value }

    /** The engine bound to the current item — internal routing state; not exposed (the UI hands one
     *  video container to the engines and each owns its own surface, so it never needs this). */
    private val _currentEngine = MutableStateFlow<MediaEngine<*>?>(null)

    private val _sleepTimerMs = MutableStateFlow<Long?>(null)
    override val sleepTimerMs: StateFlow<Long?> = _sleepTimerMs.asStateFlow()

    override fun setQueue(items: List<PlayableItem>, playIndex: Int, startPositionMs: Long, playWhenReady: Boolean) {
        if (released) return
        controller.setQueue(items, playIndex)
        publishQueue()
        playWhenReadyIntent = playWhenReady
        if (items.isEmpty()) {
            stopActive()
        } else {
            loadCurrent(startPositionMs)
        }
    }

    override fun add(items: List<PlayableItem>, at: Int?) {
        if (released) return
        controller.add(items, at)
        publishQueue()
    }

    override fun addToPlayNext(items: List<PlayableItem>) {
        if (released) return
        controller.addNext(items)
        publishQueue()
    }

    override fun move(from: Int, to: Int) {
        if (released) return
        controller.move(from, to)
        publishQueue()
    }

    override fun removeAt(index: Int) {
        if (released) return
        val removedCurrent = controller.removeAt(index)
        publishQueue()
        if (removedCurrent) {
            if (controller.state.currentIndex >= 0) loadCurrent(0L) else stopActive()
        }
    }

    override fun clearQueue() {
        if (released) return
        controller.clear()
        publishQueue()
        stopActive()
    }

    override fun playAt(index: Int) {
        if (released) return
        if (controller.moveTo(index)) {
            publishQueue()
            playWhenReadyIntent = true
            loadCurrent(0L)
        }
    }

    override fun play() {
        if (released) return
        playWhenReadyIntent = true
        _currentEngine.value?.play()
    }

    override fun pause() {
        if (released) return
        playWhenReadyIntent = false
        _currentEngine.value?.pause()
    }

    override fun togglePlay() {
        if (status.value.playWhenReady) pause() else play()
    }

    override fun stop() {
        if (released) return
        playWhenReadyIntent = false
        stopActive()
    }

    override fun seekTo(positionMs: Long) {
        _currentEngine.value?.seekTo(positionMs)
    }

    override fun skipNext() {
        if (released) return
        val next = controller.advance(userInitiated = true)
        if (next != null) {
            publishQueue()
            loadCurrent(0L)
        }
    }

    override fun skipPrev() {
        if (released) return
        if (progress.value.positionMs > SMART_PREV_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val prev = controller.previous()
        if (prev != null) {
            publishQueue()
            loadCurrent(0L)
        } else {
            seekTo(0L)
        }
    }

    override fun fastForward(ms: Long) {
        seekTo(progress.value.positionMs + ms)
    }

    override fun rewind(ms: Long) {
        seekTo((progress.value.positionMs - ms).coerceAtLeast(0L))
    }

    override fun setLoopMode(mode: LoopMode) {
        controller.setLoopMode(mode)
        publishQueue()
    }

    override fun setShuffle(enabled: Boolean) {
        controller.setShuffle(enabled)
        publishQueue()
    }

    override fun setVolume(volume: Float) = pushConfig(currentConfig.copy(volume = volume.coerceIn(0f, 1f)))

    override fun setSpeed(speed: Float) = pushConfig(currentConfig.copy(speed = speed))

    override fun setAudioOnly(audioOnly: Boolean) {
        // Audio-only is quality "zero"; leaving it restores AUTO. A fresh stamp marks the user's choice.
        val quality = if (audioOnly) VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO
        pushConfig(currentConfig.copy(preferVideoQuality = quality to nextSeq()))
    }

    override fun selectVideoQuality(quality: String) =
        pushConfig(currentConfig.copy(preferVideoQuality = quality to nextSeq()))

    override fun selectCaption(languageCode: String?) =
        pushConfig(currentConfig.copy(preferCaptionLanguage = (languageCode ?: PlaybackConfig.UNDETERMINED_LANGUAGE) to nextSeq()))

    override fun setCaptionVisible(visible: Boolean) = pushConfig(currentConfig.copy(captionVisible = visible))

    override fun setScaleMode(mode: ScaleMode) = pushConfig(currentConfig.copy(scaleMode = mode))

    /** Update the held config and push it to the active engine (the one application path). */
    private fun pushConfig(next: PlaybackConfig) {
        if (next == currentConfig) return
        currentConfig = next
        _currentEngine.value?.setConfig(next)
    }

    override fun selectAudioTrack(id: String) {
        (_currentEngine.value as? AudioTrackSelectable)?.selectAudioTrack(id)
    }

    override fun setSleepTimer(durationMs: Long?) {
        if (released) return
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimerMs.value = durationMs
        if (durationMs == null) return
        sleepJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                val step = minOf(1000L, remaining)
                delay(step)
                remaining -= step
                _sleepTimerMs.value = remaining
            }
            _sleepTimerMs.value = null
            pause()
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        localEngine?.release()
        webViewEngine?.release()
        localEngine = null
        webViewEngine = null
        _currentEngine.value = null
    }

    private fun publishQueue() {
        _queue.value = controller.state
    }

    private fun loadCurrent(startPositionMs: Long) {
        val item = controller.state.currentItem ?: return
        loadGeneration++
        when (item) {
            is PlayableItem.LocalUri -> {
                val engine = localEngine ?: engines.local().also { localEngine = it }
                bind(engine)
                engine.load(item, startPositionMs, playWhenReadyIntent, currentConfig)
            }
            is PlayableItem.YtVideoId -> {
                val factory = engines.webView
                if (factory == null) {
                    bind(null)
                    _status.value = PlaybackStatus(
                        state = PlaybackState.Error,
                        error = PlayerError("E60050", "queue item needs the WebView engine but none is configured"),
                    )
                    return
                }
                val engine = webViewEngine ?: factory().also { webViewEngine = it }
                bind(engine)
                engine.load(item, startPositionMs, playWhenReadyIntent, currentConfig)
            }
        }
    }

    /** Switch-latest: point the facade flows at [engine], stopping whatever played before. */
    private fun bind(engine: MediaEngine<*>?) {
        val previous = _currentEngine.value
        if (previous === engine) return
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        previous?.stop()
        _currentEngine.value = engine
        if (engine == null) return
        engineJobs += scope.launch {
            engine.status.collect { snapshot ->
                _status.value = snapshot
                if (snapshot.state == PlaybackState.Ended) onTrackEnded()
            }
        }
        engineJobs += scope.launch { engine.progress.collect { _progress.value = it } }
        bindCapability(engine as? VideoQualitySelectable, _videoQuality, VideoQualityState.DEFAULT) { it.videoQuality }
        bindCapability(engine as? CaptionSelectable, _captions, CaptionState.DEFAULT) { it.captions }
        bindCapability(engine as? AudioTrackSelectable, _audioTracks, AudioTrackState.DEFAULT) { it.audioTracks }
    }

    private inline fun <C : Any, S> bindCapability(
        capability: C?,
        into: MutableStateFlow<S>,
        default: S,
        crossinline flowOf: (C) -> StateFlow<S>,
    ) {
        if (capability == null) {
            into.value = default
        } else {
            engineJobs += scope.launch { flowOf(capability).collect { into.value = it } }
        }
    }

    private fun onTrackEnded() {
        if (advancedGeneration == loadGeneration) return
        advancedGeneration = loadGeneration
        val next = controller.advance(userInitiated = false)
        if (next != null) {
            publishQueue()
            loadCurrent(0L)
        }
    }

    private fun stopActive() {
        val engine = _currentEngine.value
        if (engine != null) {
            engine.stop()
        } else {
            _status.value = PlaybackStatus()
            _progress.value = Progress()
        }
    }

    private companion object {
        /** Played longer than this, "previous" restarts the current track instead. */
        const val SMART_PREV_THRESHOLD_MS = 3_000L
    }
}
