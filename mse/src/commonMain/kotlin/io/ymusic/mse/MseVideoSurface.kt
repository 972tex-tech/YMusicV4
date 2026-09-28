package io.ymusic.mse

expect class MseVideoSurface {
    /** JVM desktop: convert dest in pixels. 0×0 = decode size. No-op on Android/iOS. */
    fun setBlitSize(width: Int, height: Int)

    /** Native presents since attach (`mse_host_frames`). */
    fun presentedFrames(): Int

    fun applyAudioFx(
        volume: Float,
        speed: Float,
        pitch: Float,
        bands: FloatArray,
        bass: Float,
        virtualizer: Float,
    )
}
