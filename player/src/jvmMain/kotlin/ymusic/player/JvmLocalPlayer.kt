package ymusic.player

import kmpcommon.error.TechnicalException
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
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import java.awt.Component

/**
 * libVLC backend via VLCJ, for JVM desktop. Per the `LocalPlayer` contract the caller drives it on
 * the main (UI) thread; libVLC/VLCJ is itself thread-safe, so the position ticker runs on a
 * background dispatcher. Uses a **callback** media-player component so video renders into a
 * lightweight Swing component that a Compose `SwingPanel` can host (see the playground
 * `PlayerSurface`); audio plays regardless. Requires libVLC to be discoverable at runtime
 * (a VLC install or bundled libVLC) — [createLocalPlayer] throws a `E60020` [TechnicalException]
 * when it is not. `setSpeed` shifts pitch here (libVLC has no pitch-preserving tempo without a
 * filter) — see the module docs.
 */
internal class JvmLocalPlayer : LocalPlayer {
    private val component: CallbackMediaPlayerComponent = try {
        CallbackMediaPlayerComponent()
    } catch (t: Throwable) {
        throw TechnicalException("E60020", "libVLC not found — install VLC or bundle libVLC", cause = t)
    }
    private val vlc: MediaPlayer get() = component.mediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile private var desiredSpeed: Float = 1f

    @Volatile private var audioOnly = false

    @Volatile private var released = false

    /** The AWT video component for the desktop `PlayerSurface` to embed. */
    val videoComponent: Component get() = component

    init {
        vlc.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                // Re-assert per-load settings once libVLC is actually playing (idempotent).
                vlc.controls().setRate(desiredSpeed)
                applyAudioOnly()
                _status.update { it.copy(state = PlaybackState.Ready, playWhenReady = true, error = null) }
            }

            override fun paused(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Ready, playWhenReady = false) }

            override fun finished(mp: MediaPlayer) =
                _status.update { it.copy(state = PlaybackState.Ended, playWhenReady = false) }

            override fun error(mp: MediaPlayer) =
                _status.update {
                    it.copy(
                        state = PlaybackState.Error, playWhenReady = false,
                        error = PlayerError("E60021", "libVLC playback error"),
                    )
                }

            override fun lengthChanged(mp: MediaPlayer, newLength: Long) =
                _progress.update { it.copy(durationMs = if (newLength > 0L) newLength else Progress.DURATION_UNKNOWN) }
        })
        scope.launch { tick() }
    }

    override fun load(source: PlayableItem.LocalUri, startPositionMs: Long, playWhenReady: Boolean, config: PlaybackConfig) {
        if (released) return
        val uri = source.uri
        // The `playing` event re-asserts rate + audio-only; set them (and volume) now so they hold.
        desiredSpeed = config.speed.coerceAtLeast(0.01f)
        audioOnly = config.audioOnly
        vlc.audio().setVolume((config.volume.coerceIn(0f, 1f) * 100f).toInt())
        _status.update {
            it.copy(
                state = PlaybackState.Buffering, playWhenReady = playWhenReady, error = null, videoSize = null,
                volume = config.volume, speed = config.speed, audioOnly = config.audioOnly,
            )
        }
        _progress.value = Progress(positionMs = startPositionMs)
        // Non-blocking: play() returns immediately; the `playing`/`error` events drive the status. The
        // start position is a media option so it applies before the first frame (no post-play seek).
        val options = if (startPositionMs > 0L) arrayOf(":start-time=${startPositionMs / 1000.0}") else emptyArray()
        vlc.media().play(uri, *options)
        if (!playWhenReady) vlc.controls().setPause(true)
    }

    override fun play() {
        if (released) return
        vlc.controls().setPause(false)
        vlc.controls().setRate(desiredSpeed)
    }

    override fun pause() {
        if (released) return
        vlc.controls().setPause(true)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val clamped = positionMs.coerceAtLeast(0L)
        vlc.controls().setTime(clamped)
        _progress.update { it.copy(positionMs = clamped) }
    }

    override fun stop() {
        if (released) return
        vlc.controls().stop()
        _progress.value = Progress()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false, videoSize = null, error = null) }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        val v = volume.coerceIn(0f, 1f)
        vlc.audio().setVolume((v * 100f).toInt())
        _status.update { it.copy(volume = v) }
    }

    override fun setSpeed(speed: Float) {
        if (released) return
        desiredSpeed = speed.coerceAtLeast(0.01f)
        vlc.controls().setRate(desiredSpeed)
        _status.update { it.copy(speed = desiredSpeed) }
    }

    override fun setAudioOnly(audioOnly: Boolean) {
        if (released) return
        this.audioOnly = audioOnly
        applyAudioOnly()
        _status.update { it.copy(audioOnly = audioOnly, videoSize = if (audioOnly) null else it.videoSize) }
    }

    /** Disable/enable the libVLC video track so audio-only stops decoding video. */
    private fun applyAudioOnly() {
        val video = vlc.video()
        if (audioOnly) {
            video.setTrack(-1)
        } else {
            video.trackDescriptions().firstOrNull { it.id() != -1 }?.let { video.setTrack(it.id()) }
        }
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        component.release()
        _status.update { it.copy(state = PlaybackState.Idle, playWhenReady = false) }
    }

    private suspend fun tick() {
        while (scope.isActive) {
            if (!released && _status.value.state != PlaybackState.Idle) {
                val pos = vlc.status().time().coerceAtLeast(0L)
                val len = vlc.status().length()
                _progress.update { it.copy(positionMs = pos, durationMs = if (len > 0L) len else it.durationMs) }

                val dim = if (audioOnly) null else vlc.video().videoDimension()
                val vs = if (dim != null && dim.width > 0 && dim.height > 0) VideoSize(dim.width, dim.height) else null
                if (vs != null && vs != _status.value.videoSize) _status.update { it.copy(videoSize = vs) }
            }
            delay(TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 200L
    }
}

/** The AWT video component of a JVM `LocalPlayer`, or null for a non-JVM/non-VLCJ player. */
fun LocalPlayer.awtVideoComponent(): Component? = (this as? JvmLocalPlayer)?.videoComponent
