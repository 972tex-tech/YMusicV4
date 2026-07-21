package org.cef.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/** Compose-facing state/input endpoint for an off-screen JCEF browser. No AWT component is hosted. */
class JcefOsrSurface(val logicalSize: Dimension = Dimension(640, 360)) {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private var browser: ImageOsrBrowser? = null

    internal fun bind(browser: ImageOsrBrowser) {
        this.browser = browser
    }

    internal fun publish(frame: BufferedImage) {
        _frame.value = frame
    }

    internal fun setVisible(visible: Boolean) {
        _visible.value = visible
    }

    fun mouse(id: Int, x: Int, y: Int, button: Int = MouseEvent.NOBUTTON, modifiers: Int = 0, clickCount: Int = 0) {
        browser?.forwardMouse(id, x, y, button, modifiers, clickCount)
    }

    fun wheel(x: Int, y: Int, rotation: Int, modifiers: Int = 0) {
        browser?.forwardWheel(x, y, rotation, modifiers)
    }
}
