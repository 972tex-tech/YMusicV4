package io.ymusic.mse

import android.view.Surface

actual object MseHostNative {
    init {
        System.loadLibrary("mseffmpegjni")
    }

    @JvmStatic
    actual external fun vtable(): Long

    @JvmStatic
    actual external fun create(): Long

    @JvmStatic
    actual external fun destroy(handle: Long)

    @JvmStatic
    actual external fun frames(handle: Long): Int

    @JvmStatic
    actual external fun samplesPlayed(handle: Long): Long

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        val meta = IntArray(3)
        val bgra = copyFrameBytes(handle, afterGen, meta) ?: return null
        return NativeMseFrame(meta[0], meta[1], meta[2], bgra)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray): ByteArray?

    @JvmStatic
    external fun setVideoSurface(handle: Long, surface: Surface?)

    @JvmStatic
    external fun videoSize(handle: Long, outWh: IntArray): Boolean

    @JvmStatic
    actual external fun setVolume(handle: Long, vol: Float)

    @JvmStatic
    actual external fun setSpeed(handle: Long, speed: Float, tempo: Float)

    @JvmStatic
    actual external fun setPitch(handle: Long, pitch: Float)

    @JvmStatic
    actual external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic
    actual external fun setBass(handle: Long, db: Float)

    @JvmStatic
    actual external fun setVirtualizer(handle: Long, width: Float)
}
