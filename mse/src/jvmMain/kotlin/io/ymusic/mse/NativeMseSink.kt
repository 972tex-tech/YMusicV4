package io.ymusic.mse

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional Compose blit of decoded frames. Audio and the media clock live in native C.
 * [Flow] of bitmaps is a preview path, not a hardware surface.
 */
class NativeMseSink(val host: Long, private val surface: MseVideoSurface?) : MseHostSink {
    private val released = AtomicBoolean(false)
    /** JVM preview has no audio device; keep PCM off even if a DAC is wired later. */
    private val muted = true

    init {
        MseHostNative.setSink(host, this)
    }

    fun destroy() {
        if (!released.compareAndSet(false, true)) return
        MseHostNative.destroy(host)
        surface?.clear()
    }

    override fun onFrame(width: Int, height: Int, bgra: ByteArray) {
        surface?.publish(bgraToImage(width, height, bgra))
    }

    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (muted) return
    }

    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

    private var bufA: BufferedImage? = null
    private var bufB: BufferedImage? = null
    private var useA = true

    private fun bgraToImage(w: Int, h: Int, bgra: ByteArray): BufferedImage {
        val img = if (useA) ensure(w, h, bufA).also { bufA = it } else ensure(w, h, bufB).also { bufB = it }
        useA = !useA
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            .get(pixels, 0, minOf(pixels.size, w * h))
        return img
    }

    private fun ensure(w: Int, h: Int, cur: BufferedImage?): BufferedImage =
        if (cur != null && cur.width == w && cur.height == h) cur
        else BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
}
