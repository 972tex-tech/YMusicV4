package io.ymusic.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.awt.AWTEvent
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * NP quality/⋮/sleep menu is drawn inside the chrome DialogWindow.
 * A second NSWindow restacks chrome behind JAWT on first show; AppKit only
 * repairs that after the app deactivates. One window avoids that path.
 */
internal object OverlayMenuHost {
    var expanded by mutableStateOf(false)
    var x by mutableIntStateOf(0)
    var y by mutableIntStateOf(0)
    var w by mutableIntStateOf(0)
    var h by mutableIntStateOf(0)
    var onDismiss by mutableStateOf({})
    var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
}

@Composable
internal actual fun BoxScope.NowPlayingFloatingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (GraphicsEnvironment.isHeadless()) {
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
        return
    }
    val density = LocalDensity.current
    val anchorRight = remember { mutableIntStateOf(0) }
    val anchorBottom = remember { mutableIntStateOf(0) }
    val menuW = remember { mutableIntStateOf(0) }
    val menuH = remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { c ->
                val owner = chromeOwner() ?: return@onGloballyPositioned
                val loc = try {
                    owner.locationOnScreen
                } catch (_: Exception) {
                    return@onGloballyPositioned
                }
                val p = c.positionInWindow()
                val insets = owner.insets
                with(density) {
                    val left = loc.x + insets.left + p.x.toDp().value.toInt()
                    val top = loc.y + insets.top + p.y.toDp().value.toInt()
                    anchorRight.intValue = left + c.size.width.toDp().value.toInt()
                    anchorBottom.intValue = top + c.size.height.toDp().value.toInt()
                }
            },
    )
    if (expanded && (menuW.intValue == 0 || menuH.intValue == 0)) {
        Box(Modifier.size(0.dp)) {
            Box(
                Modifier
                    .wrapContentSize(unbounded = true)
                    .onSizeChanged {
                        with(density) {
                            menuW.intValue = it.width.toDp().value.toInt()
                            menuH.intValue = it.height.toDp().value.toInt()
                        }
                    },
            ) {
                MenuBody(content)
            }
        }
    }
    val ready = expanded &&
        menuW.intValue > 0 &&
        menuH.intValue > 0 &&
        anchorRight.intValue > 0 &&
        anchorBottom.intValue > 0
    DisposableEffect(expanded) {
        if (!expanded) return@DisposableEffect onDispose { }
        VideoOverlayNative.menuHold = true
        onDispose { VideoOverlayNative.menuHold = false }
    }
    DisposableEffect(ready, menuW.intValue, menuH.intValue, anchorRight.intValue, anchorBottom.intValue) {
        if (!ready) {
            OverlayMenuHost.expanded = false
            OverlayMenuHost.content = null
            return@DisposableEffect onDispose { }
        }
        OverlayMenuHost.x = (anchorRight.intValue - menuW.intValue).coerceAtLeast(0)
        OverlayMenuHost.y = anchorBottom.intValue
        OverlayMenuHost.w = menuW.intValue
        OverlayMenuHost.h = menuH.intValue
        OverlayMenuHost.expanded = true
        onDispose {
            OverlayMenuHost.expanded = false
            OverlayMenuHost.content = null
        }
    }
    SideEffect {
        if (ready) {
            OverlayMenuHost.onDismiss = onDismiss
            OverlayMenuHost.content = content
        }
    }
}

@Composable
internal fun OverlayMenuLayer(chromeX: Int, chromeY: Int, chromeW: Int, chromeH: Int) {
    val hostContent = OverlayMenuHost.content
    if (!OverlayMenuHost.expanded || hostContent == null || chromeW < 1 || chromeH < 1) return
    val onDismiss = OverlayMenuHost.onDismiss
    var allowDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        allowDismiss = true
    }
    DisposableEffect(onDismiss) {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = AWTEventListener { ev ->
            if (!allowDismiss) return@AWTEventListener
            when (ev) {
                is MouseEvent -> {
                    if (ev.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                    val src = ev.source as? Component ?: return@AWTEventListener
                    val win = SwingUtilities.getWindowAncestor(src) ?: src as? Window
                    val chrome = win is java.awt.Dialog && win.title == "YMusic video chrome"
                    if (!chrome) SwingUtilities.invokeLater(onDismiss)
                }
                is KeyEvent -> {
                    if (ev.id == KeyEvent.KEY_PRESSED && ev.keyCode == KeyEvent.VK_ESCAPE) {
                        SwingUtilities.invokeLater(onDismiss)
                    }
                }
            }
        }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
    var x = OverlayMenuHost.x - chromeX
    var y = OverlayMenuHost.y - chromeY
    val w = OverlayMenuHost.w
    val h = OverlayMenuHost.h
    if (x + w > chromeW) x = chromeW - w
    if (y + h > chromeH) y = chromeH - h
    if (x < 0) x = 0
    if (y < 0) y = 0
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(1f)
            .pointerInput(allowDismiss, onDismiss) {
                if (!allowDismiss) return@pointerInput
                detectTapGestures { onDismiss() }
            },
    )
    Box(Modifier.offset(x.dp, y.dp).zIndex(2f)) {
        MenuBody(hostContent)
    }
}

@Composable
private fun MenuBody(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DesignColors.SurfaceElevated,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.widthIn(min = 180.dp, max = 280.dp), content = content)
    }
}
