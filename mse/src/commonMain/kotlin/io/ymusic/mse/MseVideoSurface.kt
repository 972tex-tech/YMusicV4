package io.ymusic.mse

expect class MseVideoSurface {
    /** JVM desktop: convert dest in pixels. 0×0 = decode size. No-op on Android/iOS. */
    fun setBlitSize(width: Int, height: Int)

    /** Native presents since attach (`mse_host_frames`). */
    fun presentedFrames(): Int

    fun setVolume(volume: Float)

    fun setAudioEffect(
        bands: FloatArray,
        bass: Float,
        virtualizer: Float,
        preamp: Float,
    )

    /** Fixed-position log-frequency bars in 0..1. Returns count written. */
    fun pcmLevels(out: FloatArray): Int
}
