package io.ymusic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

/**
 * Frame/visibility endpoint for the JVM local player. Desktop paints via a Swing hole
 * (ColorSync, same as MSE) so BT.709 matches QuickTime better than Skia `Image`.
 *
 * [visible] is true while the local engine owns the shared surface (including audio-only, which shows
 * a black box because no frames arrive).
 */
class LocalVideoSurface {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var presented = 0

    fun presentedFrames(): Int = presented

    /** Swing hole. Invoked off the decode thread; must not recompose Compose. */
    @Volatile
    var swingBlit: ((BufferedImage?) -> Unit)? = null

    /** Buffer the panel is painting. Decode must not write this image. */
    @Volatile
    var swingShown: BufferedImage? = null

    /** Heavyweight Canvas for VideoToolbox. Set from AWT addNotify. */
    @Volatile
    var nativePeer: java.awt.Component? = null
        set(value) {
            field = value
            onNativePeer?.invoke(value)
        }

    @Volatile
    internal var onNativePeer: ((java.awt.Component?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(nativePeer)
        }

    /** Swing hole: skip CPU paint when the Canvas hosts AVSampleBufferDisplayLayer. */
    @Volatile
    var onNativePresent: ((Boolean) -> Unit)? = null

    /** Player thread: scale the decode blit to this Compose surface (px). */
    @Volatile
    internal var onViewSize: ((Int, Int) -> Unit)? = null
        set(value) {
            field = value
            if (value != null && blitW > 0 && blitH > 0) value(blitW, blitH)
        }

    private var blitW = 0
    private var blitH = 0

    fun setBlitSize(width: Int, height: Int) {
        blitW = width.coerceAtLeast(0)
        blitH = height.coerceAtLeast(0)
        if (blitW > 0 && blitH > 0) onViewSize?.invoke(blitW, blitH)
    }

    internal fun publish(frame: BufferedImage) {
        tickFps()
        val hole = swingBlit
        if (hole != null) hole(frame) else _frame.value = frame
    }

    @Volatile
    private var nativePresent = false

    /** HW present: no bitmap, only the overlay FPS counter. */
    internal fun presented() {
        setNativePresent(true)
        tickFps()
    }

    internal fun setNativePresent(on: Boolean) {
        if (nativePresent == on) return
        nativePresent = on
        onNativePresent?.invoke(on)
        if (on) {
            swingBlit?.invoke(null)
            swingShown = null
            _frame.value = null
        }
    }

    internal fun setVisible(visible: Boolean) {
        _visible.value = visible
        if (!visible) {
            swingBlit?.invoke(null)
            swingShown = null
            _frame.value = null
            resetFps()
        }
    }

    /** Drop the last picture without hiding the surface (audio-only: no frames, but the box stays). */
    internal fun clearFrame() {
        swingBlit?.invoke(null)
        swingShown = null
        _frame.value = null
        resetFps()
    }

    private fun tickFps() {
        presented++
    }

    private fun resetFps() {
        presented = 0
    }
}
