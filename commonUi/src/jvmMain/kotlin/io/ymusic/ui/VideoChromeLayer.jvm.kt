package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import java.awt.Color as AwtColor

private class ChromeHolder {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

/**
 * Captions + transport on a transparent child [JWindow] so they sit above the JAWT video view.
 * Headless / no owner: in-place Box (UI tests).
 */
@Composable
internal actual fun VideoChromeLayer(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val holder = remember { ChromeHolder() }
    holder.content = content
    if (GraphicsEnvironment.isHeadless()) {
        Box(modifier, content = content)
        return
    }
    var screenBounds by remember { mutableStateOf(Rectangle()) }
    val overlay = remember { arrayOfNulls<JWindow>(1) }

    Box(
        modifier.onGloballyPositioned { coords ->
            val owner = chromeOwner() ?: return@onGloballyPositioned
            val loc = try {
                owner.locationOnScreen
            } catch (_: Exception) {
                return@onGloballyPositioned
            }
            val p = coords.positionInWindow()
            val size = coords.size
            val insets = owner.insets
            val next = Rectangle(
                loc.x + insets.left + p.x.toInt(),
                loc.y + insets.top + p.y.toInt(),
                size.width,
                size.height,
            )
            if (next != screenBounds) screenBounds = next
        },
    )

    DisposableEffect(Unit) {
        val owner = chromeOwner()
        if (owner == null) {
            onDispose { }
        } else {
            val w = JWindow(owner)
            w.background = AwtColor(0, 0, 0, 0)
            w.rootPane.isOpaque = false
            w.rootPane.putClientProperty("apple.awt.draggableWindowBackground", false)
            (w.contentPane as JComponent).isOpaque = false
            w.contentPane.background = AwtColor(0, 0, 0, 0)
            w.contentPane.layout = BorderLayout()
            val panel = ComposePanel()
            panel.isOpaque = false
            panel.background = AwtColor(0, 0, 0, 0)
            panel.setContent {
                Box(Modifier.fillMaxSize(), content = holder.content)
            }
            w.contentPane.add(panel, BorderLayout.CENTER)
            overlay[0] = w
            onDispose {
                overlay[0] = null
                w.isVisible = false
                w.dispose()
            }
        }
    }

    SideEffect {
        val w = overlay[0] ?: return@SideEffect
        if (screenBounds.width <= 0 || screenBounds.height <= 0) return@SideEffect
        SwingUtilities.invokeLater {
            if (w.bounds != screenBounds) w.bounds = screenBounds
            if (!w.isVisible) w.isVisible = true
        }
    }
}

private fun chromeOwner(): Window? =
    Window.getWindows().firstOrNull { w ->
        w.isShowing && w.isDisplayable && w !is JWindow && w.width > 64 && w.height > 64
    }
