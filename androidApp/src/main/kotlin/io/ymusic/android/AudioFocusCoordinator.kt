package io.ymusic.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Audio-focus + becoming-noisy policy for the **local** engine only (ExoPlayer's built-in handling
 * is off). YouTube/MSE is not a client of this coordinator.
 *
 * Focus is requested on play and kept while paused (so a transient interruption can hand it back);
 * it is abandoned when nothing local is loaded.
 */
class AudioFocusCoordinator(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnGain = false
    private var duckedFromVolume: Float? = null
    private var noisyRegistered = false

    private fun localActive(): Boolean = player.queue.value.currentItem is PlayableItem.LocalMedia

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!localActive()) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                restoreDuck()
                if (isPlaying(player.status.value, player.queue.value)) player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying(player.status.value, player.queue.value)) {
                    resumeOnGain = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck()
            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreDuck()
                if (resumeOnGain) {
                    resumeOnGain = false
                    player.play()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                isPlaying(player.status.value, player.queue.value)
            ) {
                player.pause()
            }
        }
    }

    fun start() {
        scope.launch {
            combine(player.status, player.queue) { status, queue ->
                isPlaying(status, queue) && queue.currentItem is PlayableItem.LocalMedia
            }.distinctUntilChanged().collect { playingLocal ->
                if (playingLocal) {
                    requestFocus()
                    registerNoisy()
                } else {
                    unregisterNoisy()
                }
            }
        }
        scope.launch {
            combine(player.status, player.queue) { status, queue ->
                status.state is PlaybackState.Prepared && queue.currentItem is PlayableItem.LocalMedia
            }.distinctUntilChanged().collect { loadedLocal ->
                if (!loadedLocal) abandonFocus()
            }
        }
    }

    /** Denied focus does not block playback — the user asked to play; the system just won't arbitrate. */
    private fun requestFocus() {
        if (hasFocus) return
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        hasFocus = false
        resumeOnGain = false
    }

    private fun duck() {
        if (duckedFromVolume != null) return
        val volume = player.config.value.volume
        duckedFromVolume = volume
        player.volume = (volume * DUCK_FACTOR).coerceIn(0f, 1f)
    }

    private fun restoreDuck() {
        duckedFromVolume?.let { player.volume = it }
        duckedFromVolume = null
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisyReceiver)
        noisyRegistered = false
    }

    private companion object {
        const val DUCK_FACTOR = 0.3f
    }
}
