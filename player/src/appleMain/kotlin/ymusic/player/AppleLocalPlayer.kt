package ymusic.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusUnknown
import platform.AVFoundation.AVPlayerItemTrack
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.mediaType
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.status
import platform.AVFoundation.tracks
import platform.AVFoundation.volume
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

/**
 * AVFoundation `AVPlayer` backend — one implementation for iOS and macOS.
 *
 * Per the `LocalPlayer` contract the caller drives it on the main thread; `AVPlayer` is itself
 * thread-safe, so the internal readiness poll + position ticker run on a background dispatcher.
 * Readiness and end-of-file are detected by polling `AVPlayerItem.status` and comparing the
 * play-head to the duration, so nothing here depends on the host app's run loop delivering KVO/
 * notification callbacks (the module must work inside plain test processes too).
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleLocalPlayer : LocalPlayer {
    private val player = AVPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var desiredSpeed: Float = 1f
    private var audioOnly = false
    private var released = false

    /** The underlying player, for an iOS/macOS `AVPlayerLayer`-backed surface to bind to. */
    val player0: AVPlayer get() = player

    init {
        scope.launch { tick() }
    }

    override fun load(source: ResolvedSource, startPositionMs: Long, playWhenReady: Boolean) {
        if (released) return
        val uri = (source as? ResolvedSource.LocalUri)?.uri
        if (uri == null) {
            fail("E60004", "unsupported source: $source")
            return
        }
        val url = uri.toNsUrl()
        if (url == null) {
            fail("E60001", "bad media uri: $uri")
            return
        }
        val item = AVPlayerItem(uRL = url)
        player.replaceCurrentItemWithPlayerItem(item)
        _status.update { it.copy(state = PlaybackState.Buffering, playWhenReady = playWhenReady, error = null) }
        _progress.value = Progress(positionMs = startPositionMs)
        scope.launch { finalizeLoad(item, uri, startPositionMs, playWhenReady) }
    }

    /** Poll the item to readiness (AVPlayer prepares asynchronously) and finalize, or surface an error. */
    private suspend fun finalizeLoad(item: AVPlayerItem, uri: String, startPositionMs: Long, playWhenReady: Boolean) {
        val deadlineIterations = LOAD_TIMEOUT_MS / POLL_MS
        var i = 0L
        while (item.status == AVPlayerItemStatusUnknown) {
            if (i++ >= deadlineIterations) {
                if (player.currentItem == item) fail("E60002", "timed out preparing $uri")
                return
            }
            delay(POLL_MS)
        }
        // A newer load may have replaced this item while we polled — abandon the stale one.
        if (player.currentItem != item) return
        if (item.status == AVPlayerItemStatusFailed) {
            fail("E60003", item.error?.localizedDescription ?: "unknown AVPlayerItem failure")
            return
        }

        val durationMs = item.duration.toMillisOrUnknown()
        if (startPositionMs > 0L) player.seekToTime(cmTime(startPositionMs))
        applyAudioOnly()
        _progress.value = Progress(positionMs = startPositionMs, durationMs = durationMs)
        _status.update {
            it.copy(state = PlaybackState.Ready, audioOnly = audioOnly,
                videoSize = if (audioOnly) null else item.videoSize())
        }
        if (playWhenReady) play()
    }

    override fun play() {
        if (released) return
        player.setRate(desiredSpeed)
        _status.update { it.copy(playWhenReady = true, state = readyIfEnded(it.state)) }
    }

    override fun pause() {
        if (released) return
        player.pause()
        _status.update { it.copy(playWhenReady = false) }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekToTime(cmTime(clamped))
        _progress.update { it.copy(positionMs = clamped) }
        _status.update { it.copy(state = readyIfEnded(it.state)) }
        if (_status.value.playWhenReady) player.setRate(desiredSpeed)
    }

    override fun stop() {
        if (released) return
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false, videoSize = null, error = null) }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        val v = volume.coerceIn(0f, 1f)
        player.setVolume(v)
        _status.update { it.copy(volume = v) }
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        desiredSpeed = speed.coerceAtLeast(0.01f)
        if (_status.value.playWhenReady && _status.value.state == PlaybackState.Ready) {
            player.setRate(desiredSpeed)
        }
        _status.update { it.copy(speed = desiredSpeed) }
    }

    override fun setAudioOnly(audioOnly: Boolean) {
        if (released) return
        this.audioOnly = audioOnly
        applyAudioOnly()
        _status.update { it.copy(audioOnly = audioOnly, videoSize = if (audioOnly) null else it.videoSize) }
    }

    /** Enable/disable the video item tracks so audio-only truly stops decoding video. */
    private fun applyAudioOnly() {
        val item = player.currentItem ?: return
        item.tracks.forEach { element ->
            val track = element as? AVPlayerItemTrack ?: return@forEach
            if (track.assetTrack?.mediaType == AVMediaTypeVideo) track.setEnabled(!audioOnly)
        }
    }

    override fun release() {
        if (released) return
        released = true
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        scope.cancel()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false) }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            val item = player.currentItem
            val st = _status.value
            if (item != null && st.state != PlaybackState.Idle && st.state != PlaybackState.Error) {
                val posMs = player.currentTime().toMillisOrUnknown().coerceAtLeast(0L)
                val prevDur = _progress.value.durationMs
                val durMs = if (prevDur > 0L) prevDur else item.duration.toMillisOrUnknown()
                val ended = durMs > 0L && posMs >= durMs - END_EPSILON_MS && player.rate == 0f && st.playWhenReady
                _progress.update { it.copy(positionMs = if (ended) durMs else posMs, durationMs = durMs) }
                if (ended) {
                    _status.update { it.copy(state = PlaybackState.Ended, playWhenReady = false) }
                }
                if (!audioOnly) {
                    val vs = item.videoSize()
                    if (vs != null && vs != st.videoSize) _status.update { it.copy(videoSize = vs) }
                }
            }
            delay(TICK_MS)
        }
    }

    private fun fail(code: String, message: String) {
        _status.update { it.copy(state = PlaybackState.Error, playWhenReady = false,
            error = PlayerError(code, message)) }
    }

    private fun readyIfEnded(state: PlaybackState) =
        if (state == PlaybackState.Ended) PlaybackState.Ready else state

    private companion object {
        const val POLL_MS = 20L
        const val LOAD_TIMEOUT_MS = 15_000L
        const val TICK_MS = 200L
        const val END_EPSILON_MS = 120L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTime(ms: Long) = CMTimeMakeWithSeconds(ms / 1000.0, preferredTimescale = 1000)

/** Natural video dimensions of the item, or null if it has no visible video (audio-only file). */
@OptIn(ExperimentalForeignApi::class)
private fun AVPlayerItem.videoSize(): VideoSize? =
    presentationSize.useContents {
        if (width > 0.0 && height > 0.0) VideoSize(width.toInt(), height.toInt()) else null
    }

/** `CValue<CMTime>` → milliseconds, or [Progress.DURATION_UNKNOWN] for NaN/indefinite times. */
@OptIn(ExperimentalForeignApi::class)
private fun CValue<CMTime>.toMillisOrUnknown(): Long {
    val seconds = CMTimeGetSeconds(this)
    return if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) {
        Progress.DURATION_UNKNOWN
    } else {
        (seconds * 1000.0).toLong()
    }
}

private fun String.toNsUrl(): NSURL? =
    if (contains("://")) NSURL.URLWithString(this) else NSURL.fileURLWithPath(this)
