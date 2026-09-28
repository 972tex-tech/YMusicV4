@file:androidx.annotation.OptIn(UnstableApi::class)

package io.ymusic.player.media3

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import io.ymusic.mse.AudioFxNative
import java.nio.ByteBuffer

/**
 * Same-length software EQ/bass/virtualizer on ExoPlayer PCM. Tempo/pitch stay on Sonic
 * (this processor sits after it). Volume stays on `ExoPlayer.volume`.
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
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
        if (handle != 0L && frame > 0 && remaining % frame == 0) {
            AudioFxNative.color(handle, out, out.position(), remaining / frame, ch)
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (handle != 0L) AudioFxNative.reset(handle)
    }

    override fun onReset() {
        if (handle != 0L) AudioFxNative.reset(handle)
    }
}

/** Sonic then EQ, matching MSE: tempo/pitch → color. */
internal class AudioFxProcessorChain(
    private val fx: AudioFxProcessor,
) : AudioProcessorChain {
    private val silence = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor()
    private val processors: Array<AudioProcessor> = arrayOf(silence, sonic, fx)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silence.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silence.skippedFrames
}
