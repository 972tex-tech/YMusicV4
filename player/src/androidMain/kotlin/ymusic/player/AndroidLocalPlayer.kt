package ymusic.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

/**
 * Media3 ExoPlayer backend. ExoPlayer has strict single-thread affinity — it is bound to the main
 * looper here, so the caller must drive this player from the **main thread** (ExoPlayer throws
 * otherwise; this is the strictest backend and the reason the whole `LocalPlayer` contract is
 * main-thread). No calls are marshaled. State is driven by a [Player.Listener], not written
 * speculatively; the position ticker runs on the main dispatcher (the same thread the player is used on).
 */
internal class AndroidLocalPlayer(context: Context) : LocalPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val player = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .build()

    private var released = false
    private var audioOnly = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = syncFromPlayer(playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                syncFromPlayer(player.playbackState)

            override fun onPlayerError(error: PlaybackException) {
                _status.update {
                    it.copy(
                        state = PlaybackState.Error, playWhenReady = false,
                        error = PlayerError("E60010", error.errorCodeName + ": " + error.message),
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val size = if (audioOnly || videoSize.width == 0 || videoSize.height == 0) {
                    null
                } else {
                    VideoSize(videoSize.width, videoSize.height)
                }
                _status.update { it.copy(videoSize = size) }
            }
        })
        scope.launch { tick() }
    }

    override fun load(source: PlayableItem.LocalUri, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val uri = source.uri
        // Apply config before prepare so the first frame is already at the right settings.
        audioOnly = config.audioOnly
        player.volume = config.volume.coerceIn(0f, 1f)
        player.setPlaybackSpeed(config.speed.coerceAtLeast(0.01f))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, config.audioOnly)
            .build()
        _status.update {
            it.copy(
                state = PlaybackState.Buffering, playWhenReady = playWhenReady, error = null,
                volume = config.volume, speed = config.speed, audioOnly = config.audioOnly,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        player.setMediaItem(MediaItem.fromUri(uri.toPlayableUri()), startPositionMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    override fun play() {
        if (!released) player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        _progress.update { it.copy(positionMs = clamped) }
    }

    override fun stop() {
        if (released) return
        player.stop()
        player.clearMediaItems()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false, videoSize = null, error = null) }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        val v = volume.coerceIn(0f, 1f)
        player.volume = v
        _status.update { it.copy(volume = v) }
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        val s = speed.coerceAtLeast(0.01f)
        player.setPlaybackSpeed(s)
        _status.update { it.copy(speed = s) }
    }

    override fun setAudioOnly(audioOnly: Boolean) {
        if (released) return
        this.audioOnly = audioOnly
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
        _status.update { it.copy(audioOnly = audioOnly, videoSize = if (audioOnly) null else it.videoSize) }
    }

    /** Bind/unbind the ExoPlayer video output. Called by the Android `PlayerSurface`. */
    internal fun setVideoSurface(surface: Surface?) {
        if (!released) player.setVideoSurface(surface)
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        player.release()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false) }
    }

    private fun syncFromPlayer(playbackState: Int) {
        val mapped = when (playbackState) {
            Player.STATE_IDLE -> PlaybackState.Idle
            Player.STATE_BUFFERING -> PlaybackState.Buffering
            Player.STATE_READY -> PlaybackState.Ready
            Player.STATE_ENDED -> PlaybackState.Ended
            else -> PlaybackState.Idle
        }
        _status.update {
            it.copy(
                state = if (it.state == PlaybackState.Error) it.state else mapped,
                playWhenReady = player.playWhenReady,
            )
        }
        _progress.update { it.copy(durationMs = durationOrUnknown()) }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (!released) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val buf = player.bufferedPosition.coerceAtLeast(0L)
                _progress.update { it.copy(positionMs = pos, bufferedPositionMs = buf, durationMs = durationOrUnknown()) }
            }
            delay(TICK_MS)
        }
    }

    private fun durationOrUnknown(): Long =
        player.duration.let { if (it == C.TIME_UNSET) Progress.DURATION_UNKNOWN else it }

    private companion object {
        const val TICK_MS = 200L
    }
}

/** ExoPlayer needs a scheme; a bare path becomes `file://…`. */
private fun String.toPlayableUri(): Uri =
    if (contains("://")) Uri.parse(this) else Uri.fromFile(java.io.File(this))

/** Bind/unbind an Android video output [Surface]. A no-op for non-Android players. */
fun LocalPlayer.setVideoSurface(surface: Surface?) {
    (this as? AndroidLocalPlayer)?.setVideoSurface(surface)
}
