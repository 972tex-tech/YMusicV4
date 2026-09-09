package io.ymusic.mse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

actual class MseVideoSurface {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    var host: Long = 0L
        set(value) {
            field = value
            if (value != 0L && blitW > 0 && blitH > 0) {
                MseHostNative.setBlitSize(value, blitW, blitH)
            }
        }

    private var blitW = 0
    private var blitH = 0

    actual fun setBlitSize(width: Int, height: Int) {
        blitW = width.coerceAtLeast(0)
        blitH = height.coerceAtLeast(0)
        if (host != 0L) {
            MseHostNative.setBlitSize(host, blitW, blitH)
        }
    }

    /** Native presented video frames (`mse_host_frames`). */
    fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)

    /** DAC / pull-audio sample frames (`mse_host_samples_played`). */
    fun samplesPlayed(): Long = if (host == 0L) 0L else MseHostNative.samplesPlayed(host)

    @Volatile
    var nativePresent: Boolean = false
        private set

    /** Swing hole. Invoked off the decode thread; must not recompose Compose. */
    @Volatile
    var swingBlit: ((BufferedImage?) -> Unit)? = null

    internal fun publish(frame: BufferedImage) {
        val hole = swingBlit
        if (hole != null) hole(frame)
        else _frame.value = frame
    }

    internal fun clear() {
        swingBlit?.invoke(null)
        _frame.value = null
    }

    /** Overlay HW video on [component] when the platform can. Call from Swing after the peer exists. */
    fun attach(component: java.awt.Component?): Boolean {
        if (host == 0L) {
            nativePresent = false
            return false
        }
        nativePresent = MseHostNative.setVideoComponent(host, component)
        return nativePresent
    }
}
