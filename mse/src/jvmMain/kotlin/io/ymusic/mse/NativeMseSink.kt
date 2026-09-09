package io.ymusic.mse

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM desktop: copy pooled BGRA into a reused BufferedImage. The Swing hole
 * paints it; Compose does not recompose per frame. Audio stays in native C.
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

    override fun onFrame(width: Int, height: Int, bgra: ByteBuffer) {
        val img = grab(width, height) ?: return
        blit(img, width, height, bgra)
        lastPub = img
        surface?.publish(img)
    }

    override fun onPcm(rate: Int, channels: Int, s16: ByteArray) {
        if (muted) return
    }

    override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

    private val bufs = arrayOfNulls<BufferedImage>(3)
    private var lastPub: BufferedImage? = null

    /** Third buffer so we never write the image Swing is painting (half-frame tear). */
    private fun grab(w: Int, h: Int): BufferedImage? {
        val shown = surface?.swingShown
        val pending = lastPub
        for (i in 0..2) {
            val cur = bufs[i]
            /* null === swingShown is true before the first paint; that skipped
               every empty slot and never allocated (black, 0 fps). */
            if (cur != null && (cur === shown || cur === pending)) continue
            if (cur != null && cur.width == w && cur.height == h) return cur
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            bufs[i] = img
            return img
        }
        return null
    }

    private fun blit(img: BufferedImage, w: Int, h: Int, bgra: ByteBuffer) {
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        val n = minOf(pixels.size, w * h)
        bgra.order(ByteOrder.LITTLE_ENDIAN)
        bgra.position(0)
        bgra.limit((n * 4).coerceAtMost(bgra.capacity()))
        bgra.asIntBuffer().get(pixels, 0, n)
    }
}
