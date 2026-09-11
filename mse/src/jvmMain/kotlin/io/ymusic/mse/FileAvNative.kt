package io.ymusic.mse

import java.nio.ByteBuffer

/** JNI callbacks from the in-process libav file player (`file_av.c`). */
interface FileAvSink {
    fun onFrame(width: Int, height: Int, bgra: ByteBuffer)

    fun onPcm(rate: Int, channels: Int, s16: ByteArray)

    fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)

    fun onEvent(ended: Boolean, error: String?)
}

/**
 * Desktop file playback in the same `libmseffmpegjni` as leftover MSE. Separate C state
 * (`file_av_*`): `avformat_open_input` + PTS clock, not append/seek leftover.
 */
object FileAvNative {
    init {
        MseHostNative.vtable()
    }

    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun open(handle: Long, path: String): Int

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun play(handle: Long)

    @JvmStatic external fun pause(handle: Long)

    @JvmStatic external fun seek(handle: Long, seconds: Double)

    @JvmStatic external fun step(handle: Long): Int

    @JvmStatic external fun setSpeed(handle: Long, speed: Double)

    @JvmStatic external fun setAudioIndex(handle: Long, index: Int)

    @JvmStatic external fun setVideoEnabled(handle: Long, on: Boolean)

    /** Cap presented height in pixels. 0 = native. */
    @JvmStatic external fun setBlitMax(handle: Long, maxHeight: Int)

    @JvmStatic external fun duration(handle: Long): Double

    @JvmStatic external fun position(handle: Long): Double

    @JvmStatic external fun ended(handle: Long): Boolean

    @JvmStatic external fun videoSize(handle: Long, outWh: IntArray): Boolean

    @JvmStatic external fun lastError(handle: Long): String

    @JvmStatic external fun streams(handle: Long): Array<String>

    @JvmStatic external fun setSink(handle: Long, sink: FileAvSink?)
}
