@file:androidx.annotation.OptIn(UnstableApi::class)

package io.ymusic.player.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import io.ymusic.mse.AudioFxNative
import java.nio.ByteBuffer

/**
 * MSE-style stretch then color on ExoPlayer PCM. Volume stays on `ExoPlayer.volume`.
 * Video still follows [PlaybackParameters.speed] on the renderer.
 */
internal class AudioFxProcessor(private val handle: Long) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        if (handle != 0L) AudioFxNative.setRate(handle, inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val ch = outputAudioFormat.channelCount
        val frame = 2 * ch
        if (frame <= 0 || remaining % frame != 0) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }
        val inFrames = remaining / frame
        val inPos = inputBuffer.position()
        if (handle == 0L) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }
        val cap = (inFrames * 4 + 1024).coerceAtLeast(inFrames)
        val out = replaceOutputBuffer(cap * frame)
        val nout = AudioFxNative.stretch(handle, inputBuffer, inPos, inFrames, out, 0, cap, ch)
        inputBuffer.position(inPos + remaining)
        if (nout <= 0) {
            out.limit(0)
            return
        }
        out.position(0)
        out.limit(nout * frame)
        AudioFxNative.color(handle, out, 0, nout, ch)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (handle != 0L) AudioFxNative.reset(handle)
    }

    override fun onReset() {
        if (handle != 0L) AudioFxNative.reset(handle)
    }

    fun applyRate(speed: Float, pitch: Float) {
        if (handle == 0L) return
        AudioFxNative.setSpeed(handle, speed.coerceIn(0.25f, 4f))
        AudioFxNative.setPitch(handle, pitch.coerceIn(0.5f, 2f))
    }
}

/** Silence skip, then C stretch/pitch + EQ. */
internal class AudioFxProcessorChain(
    private val fx: AudioFxProcessor,
) : AudioProcessorChain {
    private val silence = SilenceSkippingAudioProcessor()
    private val processors: Array<AudioProcessor> = arrayOf(silence, fx)
    private var speed = 1f

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        speed = playbackParameters.speed.coerceIn(0.25f, 4f)
        fx.applyRate(speed, playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silence.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (speed == 1f) playoutDuration else (playoutDuration * speed.toDouble()).toLong()

    override fun getSkippedOutputFrameCount(): Long = silence.skippedFrames
}

/**
 * Measures how far the processed (colored) PCM runs ahead of what the AudioTrack is playing, so the
 * spectrum can read the window that is heard. Sampled on the playback thread, the only thread that
 * may touch the wrapped sink.
 */
internal class AudioFxTimingSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var frameBytes = 0
    private var sampleRate = 0
    private var bufferPtsUs = C.TIME_UNSET
    private var bufferOrigin = 0

    @Volatile private var aheadUs = 0L

    @Volatile private var aheadStampNs = 0L

    @Volatile private var speed = 1f

    @Volatile private var rate = 0

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        frameBytes = if (format.pcmEncoding == Format.NO_VALUE || format.channelCount <= 0) {
            0
        } else {
            Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
        }
        sampleRate = format.sampleRate.coerceAtLeast(0)
        rate = sampleRate
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != bufferPtsUs) {
            bufferPtsUs = presentationTimeUs
            bufferOrigin = buffer.position()
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val heardUs = getCurrentPositionUs(false)
        if (frameBytes > 0 && sampleRate > 0 && heardUs != AudioSink.CURRENT_POSITION_NOT_SET) {
            val consumedFrames = (buffer.position() - bufferOrigin) / frameBytes
            val fedUs = presentationTimeUs + consumedFrames * 1_000_000L / sampleRate
            aheadUs = (fedUs - heardUs).coerceAtLeast(0L)
            aheadStampNs = System.nanoTime()
            speed = playbackParameters.speed.coerceIn(0.25f, 4f)
        }
        return handled
    }

    override fun flush() {
        super.flush()
        bufferPtsUs = C.TIME_UNSET
        aheadUs = 0L
        aheadStampNs = 0L
    }

    /** Output frames still queued ahead of the playhead; safe from any thread. */
    fun pendingFrames(): Int {
        val stamp = aheadStampNs
        val sampleRate = rate
        if (stamp == 0L || sampleRate <= 0) return 0
        val elapsedUs = (System.nanoTime() - stamp) / 1_000
        val pendingUs = (aheadUs / speed - elapsedUs).coerceAtLeast(0f)
        return (pendingUs * sampleRate / 1_000_000f).toInt()
    }
}
