package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
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
    if (!ready) return
    val w = menuW.intValue
    val h = menuH.intValue
    val x = (anchorRight.intValue - w).coerceAtLeast(0)
    val y = anchorBottom.intValue
    // Owned by the chrome dialog so AppKit stacks it above the overlay. Owned by
    // the Frame it sits under chrome. Not focusable so it does not steal key.
    SwingDialog(
        visible = true,
        create = {
            val owner = chromeDialog() ?: chromeOwner()
            ComposeDialog(owner = owner, modalityType = Dialog.ModalityType.MODELESS).apply {
                defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
                isUndecorated = true
                isResizable = false
                focusableWindowState = false
                isAutoRequestFocus = false
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent?) = onDismiss()
                })
            }
        },
        dispose = { it.dispose() },
        update = { dialog ->
            dialog.focusableWindowState = false
            dialog.isAutoRequestFocus = false
            if (dialog.x != x || dialog.y != y) dialog.setLocation(x, y)
            if (dialog.width != w || dialog.height != h) dialog.setSize(w, h)
        },
    ) {
        val allowDismiss = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(200)
            allowDismiss.value = true
        }
        DisposableEffect(window) {
            val menu = window
            menu.focusableWindowState = false
            menu.isAutoRequestFocus = false
            val toolkit = Toolkit.getDefaultToolkit()
            val listener = AWTEventListener { ev ->
                if (!allowDismiss.value) return@AWTEventListener
                when (ev) {
                    is MouseEvent -> {
                        if (ev.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
                        val src = ev.source as? Component ?: return@AWTEventListener
                        val win = SwingUtilities.getWindowAncestor(src) ?: src as? Window
                        if (win != menu) SwingUtilities.invokeLater(onDismiss)
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
        MenuBody(content)
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
