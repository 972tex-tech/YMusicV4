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
        var lastW = -1
        var lastH = -1
        fun ownerDump() =
            "size=${owner.width}x${owner.height} loc=${owner.location} " +
                "state=${(owner as? Frame)?.extendedState} last=${lastW}x$lastH"
        fun park(why: String) {
            overlayLog("PARK $why ${ownerDump()}")
            lastW = owner.width
            lastH = owner.height
            // AWT resize callbacks run inside Compose measure on desktop.
            // Writing snapshot state here crashes: layout state is not idle.
            SwingUtilities.invokeLater {
                parked = true
                parkGen++
            }
        }
        // Owned dialogs (quality/⋮) fire moved/resized with the same size. Skip
        // the first event and any later zero-delta so chrome does not blink.
        fun parkIfSizeChanged(why: String) {
            val w = owner.width
            val h = owner.height
            overlayLog("owner $why ${ownerDump()}")
            if (w == lastW && h == lastH) {
                overlayLog("owner $why skip same-size")
                return
            }
            val first = lastW < 0
            lastW = w
            lastH = h
            if (first) {
                overlayLog("owner $why skip first")
                return
            }
            park(why)
        }
        val components = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = parkIfSizeChanged("moved")
            override fun componentResized(e: ComponentEvent) = parkIfSizeChanged("resized")
        }
        val windows = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                overlayLog("owner stateChanged old=${e.oldState} new=${e.newState} ${ownerDump()}")
                park("stateChanged")
            }
            override fun windowIconified(e: WindowEvent) {
                overlayLog("owner iconified ${ownerDump()}")
                park("iconified")
            }
            override fun windowActivated(e: WindowEvent) =
                overlayLog("owner activated opp=${e.oppositeWindow}")
            override fun windowDeactivated(e: WindowEvent) =
                overlayLog("owner deactivated opp=${e.oppositeWindow}")
            override fun windowGainedFocus(e: WindowEvent) =
                overlayLog("owner gainedFocus opp=${e.oppositeWindow}")
            override fun windowLostFocus(e: WindowEvent) =
                overlayLog("owner lostFocus opp=${e.oppositeWindow}")
        }
        owner.addComponentListener(components)
        owner.addWindowStateListener(windows)
        owner.addWindowListener(windows)
        owner.addWindowFocusListener(windows)
        onDispose {
            owner.removeComponentListener(components)
            owner.removeWindowStateListener(windows)
            owner.removeWindowListener(windows)
            owner.removeWindowFocusListener(windows)
        }
    }

    LaunchedEffect(parkGen) {
        if (!parked) return@LaunchedEffect
        overlayLog("parked; unpark in 180ms gen=$parkGen")
        delay(180)
        overlayLog("unpark gen=$parkGen")
        parked = false
    }

    LaunchedEffect(bounds.w, bounds.h) {
        if (bounds.w <= 0 || bounds.h <= 0) {
            overlayLog("chromeReady=false bounds=$bounds")
            chromeReady = false
            return@LaunchedEffect
        }
        delay(50)
        overlayLog("chromeReady=true bounds=$bounds")
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
                overlayLog("bounds $bounds -> $next")
                SwingUtilities.invokeLater { bounds = next }
            }
        },
    )

    val dlgVisible = !parked && !VideoOverlayNative.coverHole
    LaunchedEffect(parked, VideoOverlayNative.coverHole, chromeReady, dlgVisible, bounds) {
        overlayLog(
            "compose parked=$parked cover=${VideoOverlayNative.coverHole} ready=$chromeReady " +
                "dlgVisible=$dlgVisible bounds=$bounds overlayVis=${VideoOverlayNative.visible} " +
                "armed=${VideoOverlayNative.armed}",
        )
    }

    if (!chromeReady || bounds.w <= 0 || bounds.h <= 0) return

    val state = rememberDialogState(
        position = WindowPosition(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.w.dp, bounds.h.dp),
    )
    LaunchedEffect(bounds, parked) {
        if (parked) return@LaunchedEffect
        overlayLog("apply chrome state pos=${bounds.x},${bounds.y} size=${bounds.w}x${bounds.h}")
        state.position = WindowPosition(bounds.x.dp, bounds.y.dp)
        state.size = DpSize(bounds.w.dp, bounds.h.dp)
    }

    // Owned modeless dialog stays above the player window. Do not use
    // alwaysOnTop or click-forward — that froze AppKit.
    DialogWindow(
        onCloseRequest = {},
        state = state,
        visible = dlgVisible,
        title = "YMusic video chrome",
        decoration = WindowDecoration.Undecorated(),
        transparent = true,
        resizable = false,
        // Not key: a key chrome drops behind JAWT when the Frame is clicked
        // (sticky overlay blink on pause). Mouse still reaches the overlay.
        focusable = false,
        alwaysOnTop = false,
        modalityType = DialogModalityType.Modeless,
    ) {
        DisposableEffect(window) {
            val w = window
            w.focusableWindowState = false
            w.isAutoRequestFocus = false
            overlayLog(
                "chrome window attach showing=${w.isShowing} vis=${w.isVisible} " +
                    "loc=${w.location} size=${w.size} owner=${w.owner}",
            )
            val raise = {
                overlayLog("chrome toFront showing=${w.isShowing} vis=${w.isVisible}")
                if (w.isDisplayable && w.isShowing) w.toFront()
            }
            VideoOverlayNative.raiseWindow = raise
            val win = object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) =
                    overlayLog("chrome activated opp=${e.oppositeWindow}")
                override fun windowDeactivated(e: WindowEvent) =
                    overlayLog("chrome deactivated opp=${e.oppositeWindow}")
                override fun windowGainedFocus(e: WindowEvent) =
                    overlayLog("chrome gainedFocus opp=${e.oppositeWindow}")
                override fun windowLostFocus(e: WindowEvent) =
                    overlayLog("chrome lostFocus opp=${e.oppositeWindow}")
                override fun windowOpened(e: WindowEvent) = overlayLog("chrome opened")
                override fun windowClosed(e: WindowEvent) = overlayLog("chrome closed")
                override fun windowClosing(e: WindowEvent) = overlayLog("chrome closing")
            }
            val comp = object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) =
                    overlayLog("chrome shown loc=${w.location} size=${w.size}")
                override fun componentHidden(e: ComponentEvent) =
                    overlayLog("chrome hidden")
                override fun componentMoved(e: ComponentEvent) =
                    overlayLog("chrome moved loc=${w.location}")
                override fun componentResized(e: ComponentEvent) =
                    overlayLog("chrome resized size=${w.size}")
            }
            w.addWindowListener(win)
            w.addWindowFocusListener(win)
            w.addComponentListener(comp)
            onDispose {
                overlayLog("chrome window detach")
                w.removeWindowListener(win)
                w.removeWindowFocusListener(win)
                w.removeComponentListener(comp)
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
