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
}
