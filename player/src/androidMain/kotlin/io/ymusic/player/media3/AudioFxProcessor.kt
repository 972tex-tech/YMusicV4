@file:androidx.annotation.OptIn(UnstableApi::class)

package io.ymusic.player.media3

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
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
