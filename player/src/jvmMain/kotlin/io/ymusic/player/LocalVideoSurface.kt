package io.ymusic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

/**
 * Compose-facing frame/visibility endpoint for the JVM local (ffmpeg) player, rendered **off-screen**.
 * No AWT component is hosted, so decoded frames reach the UI as a plain Compose `Image`
 * (`toComposeImageBitmap`) instead of a `SwingPanel`. That removes the AWT interop hole — Compose
 * popups/menus drawn over the video receive mouse input again.
 *
 * [visible] is true while the local engine owns the shared surface (including audio-only, which shows
 * a black box because no frames arrive); [frame] is the latest decoded picture, or null when there is
 * nothing to show (idle, or audio-only with the video track disabled).
 */
class LocalVideoSurface {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Player thread: scale the decode blit to this Compose surface (px). */
    @Volatile
    internal var onViewSize: ((Int, Int) -> Unit)? = null

    fun setBlitSize(width: Int, height: Int) {
        if (width > 0 && height > 0) onViewSize?.invoke(width, height)
    }

    internal fun publish(frame: BufferedImage) {
        _frame.value = frame
    }

    internal fun setVisible(visible: Boolean) {
        _visible.value = visible
        if (!visible) _frame.value = null
    }

    /** Drop the last picture without hiding the surface (audio-only: no frames, but the box stays). */
    internal fun clearFrame() {
        _frame.value = null
    }
}
