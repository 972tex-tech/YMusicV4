package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.delay
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

private class ChromeHolder {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

private data class ChromeBounds(val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0)

/** Keep the owner's frame edge free for live resize. */
private const val EDGE_PAD = 8

/**
 * Captions + transport in a transparent Compose window above the JAWT video view.
 * Headless: in-place Box (UI tests).
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(ChromeBounds()) }
    var parked by remember { mutableStateOf(false) }
    var parkGen by remember { mutableIntStateOf(0) }
    // Don't spawn the overlay NSWindow in the same frame as JAWT re-attach
    // (mini → Now Playing). That freeze is EDT + AppKit re-entry.
    var chromeReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val owner = chromeOwner() ?: return@DisposableEffect onDispose { }
        fun park() {
            // AWT resize callbacks run inside Compose measure on desktop.
            // Writing snapshot state here crashes: layout state is not idle.
            SwingUtilities.invokeLater {
                parked = true
                parkGen++
            }
        }
        val components = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = park()
            override fun componentResized(e: ComponentEvent) = park()
        }
        val windows = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) = park()
            override fun windowIconified(e: WindowEvent) = park()
        }
        owner.addComponentListener(components)
        owner.addWindowStateListener(windows)
        owner.addWindowListener(windows)
        onDispose {
            owner.removeComponentListener(components)
            owner.removeWindowStateListener(windows)
            owner.removeWindowListener(windows)
        }
    }

    LaunchedEffect(parkGen) {
        if (!parked) return@LaunchedEffect
        delay(180)
        parked = false
    }

    LaunchedEffect(bounds.w, bounds.h) {
        if (bounds.w <= 0 || bounds.h <= 0) {
            chromeReady = false
            return@LaunchedEffect
        }
        delay(50)
        chromeReady = true
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
            // AWT screen coords are points; Compose layout is px.
            val next = with(density) {
                ChromeBounds(
                    x = loc.x + insets.left + p.x.toDp().value.toInt(),
                    y = loc.y + insets.top + p.y.toDp().value.toInt(),
                    w = size.width.toDp().value.toInt(),
                    h = size.height.toDp().value.toInt(),
                ).insetFromOwner(owner)
            }
            if (next != bounds) {
                SwingUtilities.invokeLater { bounds = next }
            }
        },
    )

    if (!chromeReady || bounds.w <= 0 || bounds.h <= 0) return

    val state = rememberDialogState(
        position = WindowPosition(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.w.dp, bounds.h.dp),
    )
    LaunchedEffect(bounds, parked) {
        if (parked) return@LaunchedEffect
        state.position = WindowPosition(bounds.x.dp, bounds.y.dp)
        state.size = DpSize(bounds.w.dp, bounds.h.dp)
    }

    // Owned modeless dialog stays above the player window. Do not use
    // alwaysOnTop or click-forward — that froze AppKit.
    DialogWindow(
        onCloseRequest = {},
        state = state,
        visible = !parked,
        title = "YMusic video chrome",
        decoration = WindowDecoration.Undecorated(),
        transparent = true,
        resizable = false,
        focusable = true,
        alwaysOnTop = false,
        modalityType = DialogModalityType.Modeless,
    ) {
        DisposableEffect(window) {
            val w = window
            val raise = { if (w.isDisplayable && w.isShowing) w.toFront() }
            VideoOverlayNative.raiseWindow = raise
            onDispose {
                if (VideoOverlayNative.raiseWindow === raise) VideoOverlayNative.raiseWindow = null
            }
        }
        Box(Modifier.fillMaxSize(), content = holder.content)
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

internal fun chromeOwner(): Window? =
    Window.getWindows()
        .filter { w ->
            w is Frame && w.isShowing && w.isDisplayable && !w.isUndecorated && w.width > 64 && w.height > 64
        }
        .maxByOrNull { it.width * it.height }
