package io.ymusic.mse

import java.nio.ByteBuffer

/** Standalone [audio_fx.c] handle for Media3's PCM processor. Volume/speed stay on ExoPlayer. */
object AudioFxNative {
    init {
        MseHostNative.vtable()
    }

    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun reset(handle: Long)

    @JvmStatic external fun setRate(handle: Long, rate: Int)

    @JvmStatic external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic external fun setBass(handle: Long, db: Float)

    @JvmStatic external fun setVirtualizer(handle: Long, width: Float)

    @JvmStatic external fun color(handle: Long, buf: ByteBuffer, pos: Int, frames: Int, ch: Int)
}
