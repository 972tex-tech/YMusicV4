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

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private var framesInWindow = 0
    private var windowStartNs = 0L

    /** Swing hole. Invoked off the decode thread; must not recompose Compose. */
    @Volatile
    var swingBlit: ((BufferedImage?) -> Unit)? = null

    /** Buffer the panel is painting. Decode must not write this image. */
    @Volatile
    var swingShown: BufferedImage? = null

    /** Player thread: scale the decode blit to this Compose surface (px). */
    @Volatile
    internal var onViewSize: ((Int, Int) -> Unit)? = null

    fun setBlitSize(width: Int, height: Int) {
        if (width > 0 && height > 0) onViewSize?.invoke(width, height)
    }

    internal fun publish(frame: BufferedImage) {
        tickFps()
        val hole = swingBlit
        if (hole != null) hole(frame) else _frame.value = frame
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
        val now = System.nanoTime()
        if (windowStartNs == 0L) windowStartNs = now
        framesInWindow++
        val dt = now - windowStartNs
        if (dt >= 1_000_000_000L) {
            _fps.value = (framesInWindow * 1_000_000_000.0 / dt).toInt()
            framesInWindow = 0
            windowStartNs = now
        }
    }

    private fun resetFps() {
        framesInWindow = 0
        windowStartNs = 0L
        _fps.value = 0
    }
}
