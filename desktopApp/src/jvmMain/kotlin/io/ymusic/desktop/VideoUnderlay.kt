package io.ymusic.desktop

import java.awt.Color
import java.awt.Frame
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Video-only owner Frame behind the transparent Compose UI. Owned dialogs stay above this
 * Frame, so the canvas cannot restack over chrome. Never toFront, never key.
 */
internal class VideoUnderlay {
    val frame: Frame = Frame("YMusic video").apply {
        isUndecorated = true
        isResizable = false
        focusableWindowState = false
        isAutoRequestFocus = false
        background = Color.BLACK
        layout = java.awt.BorderLayout()
        setLocation(0, 0)
        setSize(1, 1)
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
            if (!frame.isVisible) frame.isVisible = true
            return
        }
        if (frame.x != x || frame.y != y || frame.width != w || frame.height != h) {
            frame.setBounds(x, y, w, h)
        }
        if (!frame.isVisible) frame.isVisible = true
    }

    fun dispose() {
        frame.removeAll()
        frame.dispose()
    }
}
