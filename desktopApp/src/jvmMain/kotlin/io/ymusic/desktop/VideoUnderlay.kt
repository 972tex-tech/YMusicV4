package io.ymusic.desktop

import io.ymusic.mse.FileAvNative
import java.awt.Color
import java.awt.Frame
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Video-only owner Frame behind the transparent Compose UI. Owned dialogs stay above this
 * Frame, so the canvas cannot restack over chrome. Never toFront, never key.
 */
internal class VideoUnderlay {
    val frame: Frame = Frame("YMusic video").apply { // title matched in mse_vt_present fullscreen restack
        isUndecorated = true
        isResizable = false
        focusableWindowState = false
        isAutoRequestFocus = false
        background = Color.BLACK
        layout = java.awt.BorderLayout()
        setLocation(0, 0)
        setSize(1, 1)
    }

    /** Follows the main window: a separate top-level frame on X11/Windows, it would outlive a hide. */
    var shown = true
        set(value) {
            field = value
            frame.isVisible = value
        }

    fun attach(panel: JComponent?) {
        frame.removeAll()
        if (panel != null) frame.add(panel, java.awt.BorderLayout.CENTER)
        frame.validate()
        frame.repaint()
    }

    fun setHole(x: Int, y: Int, w: Int, h: Int) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { setHole(x, y, w, h) }
            return
        }
        if (w < 1 || h < 1) {
            if (frame.width != 1 || frame.height != 1) frame.setBounds(0, 0, 1, 1)
            if (shown && !frame.isVisible) frame.isVisible = true
            return
        }
        if (frame.x != x || frame.y != y || frame.width != w || frame.height != h) {
            frame.setBounds(x, y, w, h)
        }
        if (shown && !frame.isVisible) frame.isVisible = true
    }

    /** Sit immediately under [front] so another app cannot stay in the hole. Not key. */
    fun orderJustBehind(front: Window) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { orderJustBehind(front) }
            return
        }
        if (!frame.isDisplayable || !front.isDisplayable) return
        if (!isMac) {
            if (!frame.isVisible) return
            // AWT has no relative restack: raise the pair in order. The frame is unfocusable, so focus stays on front.
            frame.toFront()
            front.toFront()
            return
        }
        try {
            FileAvNative.orderWindowBelow(frame, front)
        } catch (_: Throwable) {
        }
    }

    fun dispose() {
        frame.removeAll()
        frame.dispose()
    }
}

private val isMac = "mac" in System.getProperty("os.name").orEmpty().lowercase()
