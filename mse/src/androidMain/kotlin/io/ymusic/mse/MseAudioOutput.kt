package io.ymusic.mse

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Plays one MSE host's PCM ring through Media3's [DefaultAudioSink]: a mixer AudioTrack that equalizer
 * apps can attach to, playout timestamps for the host's A/V clock, and a new track after audioserver
 * dies. DefaultAudioSink allows a single looper, so every sink call runs on [thread].
 */
@OptIn(UnstableApi::class)
internal class MseAudioOutput(private val host: Long) {
    private val thread = HandlerThread("mse-audio", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val state = IntArray(STATE_SIZE)
    private val step = Runnable { step() }
    private lateinit var sink: DefaultAudioSink

    @Volatile
    private var released = false
    private var gen = -1
    private var rate = 0
    private var channels = 0
    private var configured = false
    private var running = false
    private var draining = false
    private var failing = false

    /** Frames in [buffer] the sink has not taken yet; 0 = [buffer] is free. */
    private var heldFrames = 0
    private var writtenFrames = 0L

    /** Highest position reported in this generation: AudioTrack timestamps can step backwards. */
    private var reportedFrames = -1L

    init {
        handler.post {
            @Suppress("DEPRECATION") // Builder(Context) only adds encoded-passthrough detection; this is PCM.
            sink = DefaultAudioSink.Builder()
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(MIN_TRACK_BUFFER_US)
                        .setMaxPcmBufferDurationUs(MAX_TRACK_BUFFER_US)
                        .build(),
                )
                .build()
            sink.setListener(SinkListener())
            sink.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            MseHostNative.dacAttach(host, true)
            step()
        }
    }

    /** Blocks until the sink is gone and the host no longer feeds it; call before destroying the host. */
    fun release() {
        released = true
        val done = CountDownLatch(1)
        handler.post {
            handler.removeCallbacks(step)
            MseHostNative.dacAttach(host, false)
            sink.reset()
            sink.release()
            done.countDown()
        }
        done.await()
        thread.quitSafely()
    }

    private fun step() {
        if (released) return
        val free = heldFrames == 0
        val n = MseHostNative.dacPoll(host, buffer, if (free) buffer.capacity() else 0, PAUSED_WAIT_MS, state)
        if (n < 0) return
        if (state[GEN] != gen || state[RATE] != rate || state[CHANNELS] != channels) {
            sink.flush()
            gen = state[GEN]
            heldFrames = 0
            writtenFrames = 0
            reportedFrames = -1
            draining = false
            if (state[RATE] != rate || state[CHANNELS] != channels) {
                rate = state[RATE]
                channels = state[CHANNELS]
                configured = configure()
            }
        }
        if (n > 0 && configured) {
            // More audio after a drain (a live stream grew): the stopped track cannot take it.
            if (draining) {
                sink.flush()
                draining = false
            }
            buffer.clear()
            buffer.limit(n)
            heldFrames = n / (channels * BYTES_PER_SAMPLE)
        }
        val run = state[RUN] != 0
        if (run != running) {
            running = run
            if (run) sink.play() else sink.pause()
        }
        var full = false
        try {
            if (heldFrames > 0) {
                if (sink.handleBuffer(buffer, writtenFrames * MICROS_PER_SECOND / rate, 1)) {
                    writtenFrames += heldFrames
                    heldFrames = 0
                } else {
                    full = true
                }
            }
            if (state[DRAIN] != 0 && heldFrames == 0 && writtenFrames > 0) {
                sink.playToEndOfStream()
                draining = true
            }
            failing = false
        } catch (error: AudioSink.InitializationException) {
            recover(error)
            return
        } catch (error: AudioSink.WriteException) {
            recover(error)
            return
        }
        report()
        if (n > 0 && !full) handler.post(step) else handler.postDelayed(step, IDLE_MS)
    }

    private fun report() {
        val positionUs = sink.getCurrentPositionUs(false)
        if (positionUs != AudioSink.CURRENT_POSITION_NOT_SET && rate > 0) {
            reportedFrames = maxOf(reportedFrames, positionUs * rate / MICROS_PER_SECOND)
        }
        val bufferUs = sink.audioTrackBufferSizeUs.takeIf { it != C.TIME_UNSET } ?: 0L
        MseHostNative.dacReport(host, gen, reportedFrames, writtenFrames, sink.hasPendingData(), bufferUs)
    }

    private fun configure(): Boolean {
        if (rate <= 0 || channels <= 0) return false
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .build()
        return try {
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            true
        } catch (error: AudioSink.ConfigurationException) {
            MseLogBridge.fire(LOG_WARN, "mse audio sink: cannot play ${rate}Hz/${channels}ch: $error")
            false
        }
    }

    /**
     * A flush drops the broken track; the next handleBuffer opens a new one. The held chunk is
     * resubmitted at the old timestamp, so the clock carries on past the audio the track lost.
     */
    private fun recover(error: Exception) {
        if (!failing) MseLogBridge.fire(LOG_WARN, "mse audio sink failed, reopening: $error")
        failing = true
        sink.flush()
        handler.postDelayed(step, RETRY_MS)
    }

    private inner class SinkListener : AudioSink.Listener {
        override fun onPositionDiscontinuity() = Unit

        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            MseLogBridge.fire(LOG_INFO, "mse audio session $audioSessionId")
            MseHostNative.dacSession(host, audioSessionId)
        }
    }

    private companion object {
        const val GEN = 0
        const val RATE = 1
        const val CHANNELS = 2
        const val RUN = 3
        const val DRAIN = 4
        const val STATE_SIZE = 5

        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_BYTES = 4096 * 2 * BYTES_PER_SAMPLE
        const val MICROS_PER_SECOND = 1_000_000L
        const val IDLE_MS = 10L
        const val RETRY_MS = 100L
        const val PAUSED_WAIT_MS = 200

        /**
         * A streaming AudioTrack starts once its buffer is full, and the host decodes only a little
         * past its clock, so the track buffer stays small (the host's ring is the real buffer).
         */
        const val MIN_TRACK_BUFFER_US = 100_000
        const val MAX_TRACK_BUFFER_US = 200_000

        /** MSE_LOG_INFO / MSE_LOG_WARN. */
        const val LOG_INFO = 1
        const val LOG_WARN = 2
    }
}
