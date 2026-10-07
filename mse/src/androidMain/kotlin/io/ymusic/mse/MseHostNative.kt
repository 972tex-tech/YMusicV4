package io.ymusic.mse

import android.view.Surface

actual object MseHostNative {
    private var frameScratch = ByteArray(0)
    private val frameMeta = IntArray(3)

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
        var n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
        if (n <= 0) return null
        if (n > frameScratch.size) {
            frameScratch = ByteArray(n)
            n = copyFrameBytes(handle, afterGen, frameMeta, frameScratch)
            if (n <= 0 || n > frameScratch.size) return null
        }
        return NativeMseFrame(frameMeta[0], frameMeta[1], frameMeta[2], frameScratch)
    }

    @JvmStatic
    private external fun copyFrameBytes(handle: Long, afterGen: Int, meta: IntArray, dest: ByteArray): Int

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

    actual external fun setPreamp(handle: Long, db: Float)

    @JvmStatic
    actual external fun pcmLevels(handle: Long, out: FloatArray): Int
}
