package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private class ChromeHolder {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

private data class ChromeBounds(val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0)

/**
 * Captions + transport in a transparent Compose window above the JAWT video view.
 * Headless: in-place Box (UI tests).
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
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(ChromeBounds()) }

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
            /* AWT screen coords are points; Compose layout is px. */
            val next = with(density) {
                ChromeBounds(
                    x = loc.x + insets.left + p.x.toDp().value.toInt(),
                    y = loc.y + insets.top + p.y.toDp().value.toInt(),
                    w = size.width.toDp().value.toInt(),
                    h = size.height.toDp().value.toInt(),
                )
            }
            if (next != bounds) bounds = next
        },
    )

    if (bounds.w <= 0 || bounds.h <= 0) return

    val state = rememberWindowState(
        position = WindowPosition(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.w.dp, bounds.h.dp),
    )
    LaunchedEffect(bounds) {
        state.position = WindowPosition(bounds.x.dp, bounds.y.dp)
        state.size = DpSize(bounds.w.dp, bounds.h.dp)
    }

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = true,
        alwaysOnTop = false,
        title = "YMusic video chrome",
    ) {
        DisposableEffect(Unit) {
            window.focusableWindowState = true
            val owner = chromeOwner()
            val listener = object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    if (window.isDisplayable) window.toFront()
                }
            }
            owner?.addWindowListener(listener)
            window.toFront()
            onDispose { owner?.removeWindowListener(listener) }
        }
        Box(Modifier.fillMaxSize(), content = holder.content)
    }
}

private fun chromeOwner(): Window? =
    Window.getWindows()
        .filter { w ->
            w is Frame && w.isShowing && w.isDisplayable && !w.isUndecorated && w.width > 64 && w.height > 64
        }
        .maxByOrNull { it.width * it.height }
