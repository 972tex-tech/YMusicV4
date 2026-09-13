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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import java.awt.AWTEvent
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

private class ChromeHolder {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

private data class ChromeBounds(val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0)

/** Leave the owner's frame edge free so the window can be resized/dragged. */
private const val EDGE_PAD = 8

/**
 * One overlay [ComposeWindow] for captions + transport. Hidden (not disposed) on drag/resize
 * and when the layer leaves composition — disposing during fullscreen exit deadlocks AWT.
 */
@OptIn(ExperimentalComposeUiApi::class)
private object ChromeOverlay {
    val holder = ChromeHolder()
    private var window: ComposeWindow? = null

    fun ensure(): ComposeWindow {
        window?.let { if (it.isDisplayable) return it else window = null }
        val w = ComposeWindow()
        w.isUndecorated = true
        w.isResizable = false
        w.isTransparent = true
        w.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        w.setContent {
            Box(Modifier.fillMaxSize(), content = holder.content)
        }
        window = w
        return w
    }

    fun apply(bounds: ChromeBounds, visible: Boolean) {
        SwingUtilities.invokeLater {
            val w = ensure()
            if (visible && bounds.w > 0 && bounds.h > 0) {
                w.setBounds(bounds.x, bounds.y, bounds.w, bounds.h)
                if (!w.isVisible) w.isVisible = true
            } else if (w.isVisible) {
                w.isVisible = false
            }
        }
    }

    fun hide() {
        val w = window ?: return
        SwingUtilities.invokeLater {
            if (w.isDisplayable && w.isVisible) w.isVisible = false
        }
    }
}

/**
 * Captions + transport above the JAWT video view. Headless: in-place Box (UI tests).
 */
@Composable
internal actual fun VideoChromeLayer(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    ChromeOverlay.holder.content = content
    if (GraphicsEnvironment.isHeadless()) {
        Box(modifier, content = content)
        return
    }
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(ChromeBounds()) }
    var parked by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val owner = chromeOwner() ?: return@DisposableEffect onDispose { ChromeOverlay.hide() }
        val components = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                parked = true
            }
            override fun componentResized(e: ComponentEvent) {
                parked = true
            }
        }
        val windows = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                parked = true
            }
            override fun windowIconified(e: WindowEvent) {
                parked = true
            }
        }
        owner.addComponentListener(components)
        owner.addWindowStateListener(windows)
        owner.addWindowListener(windows)
        val mouseUp = AWTEventListener { ev ->
            if (ev is MouseEvent && ev.id == MouseEvent.MOUSE_RELEASED) parked = false
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseUp, AWTEvent.MOUSE_EVENT_MASK)
        onDispose {
            parked = true
            ChromeOverlay.hide()
            owner.removeComponentListener(components)
            owner.removeWindowStateListener(windows)
            owner.removeWindowListener(windows)
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseUp)
        }
    }

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
            val next = with(density) {
                ChromeBounds(
                    x = loc.x + insets.left + p.x.toDp().value.toInt(),
                    y = loc.y + insets.top + p.y.toDp().value.toInt(),
                    w = size.width.toDp().value.toInt(),
                    h = size.height.toDp().value.toInt(),
                ).insetFromOwner(owner)
            }
            if (next != bounds) {
                bounds = next
                parked = false
            }
        },
    )

    SideEffect {
        ChromeOverlay.apply(bounds, visible = !parked && bounds.w > 0 && bounds.h > 0)
    }
}

private fun ChromeBounds.insetFromOwner(owner: Window, pad: Int = EDGE_PAD): ChromeBounds {
    val loc = try {
        owner.locationOnScreen
    } catch (_: Exception) {
        return this
    }
    val left = loc.x + pad
    val top = loc.y + pad
    val right = loc.x + owner.width - pad
    val bottom = loc.y + owner.height - pad
    var x = this.x
    var y = this.y
    var w = this.w
    var h = this.h
    if (x < left) {
        w -= left - x
        x = left
    }
    if (y < top) {
        h -= top - y
        y = top
    }
    if (x + w > right) w = right - x
    if (y + h > bottom) h = bottom - y
    if (w < 1 || h < 1) return ChromeBounds()
    return ChromeBounds(x, y, w, h)
}

private fun chromeOwner(): Window? =
    Window.getWindows()
        .filter { w ->
            w is Frame && w.isShowing && w.isDisplayable && !w.isUndecorated && w.width > 64 && w.height > 64
        }
        .maxByOrNull { it.width * it.height }
