package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.yield
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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
                    val w = c.size.width.toDp().value.toInt()
                    val h = c.size.height.toDp().value.toInt()
                    anchorRight.intValue = left + w
                    anchorBottom.intValue = top + h
                }
            },
    )
    if (!expanded) return
    val state = rememberDialogState(
        position = WindowPosition(anchorRight.intValue.dp, anchorBottom.intValue.dp),
        size = DpSize(280.dp, 800.dp),
    )
    fun place() {
        val w = menuW.intValue
        val h = menuH.intValue
        val right = anchorRight.intValue
        val bottom = anchorBottom.intValue
        if (w <= 0 || h <= 0 || right <= 0 || bottom <= 0) return
        SwingUtilities.invokeLater {
            state.size = DpSize(w.dp, h.dp)
            state.position = WindowPosition((right - w).coerceAtLeast(0).dp, bottom.dp)
        }
    }
    LaunchedEffect(anchorRight.intValue, anchorBottom.intValue, menuW.intValue, menuH.intValue) {
        place()
    }
    DialogWindow(
        onCloseRequest = onDismiss,
        state = state,
        title = "",
        decoration = WindowDecoration.Undecorated(),
        transparent = false,
        resizable = false,
        focusable = true,
        alwaysOnTop = false,
        modalityType = DialogModalityType.Modeless,
    ) {
        val allowDismiss = remember { mutableStateOf(false) }
        LaunchedEffect(menuW.intValue, menuH.intValue) {
            if (menuW.intValue > 0 && menuH.intValue > 0) {
                yield()
                allowDismiss.value = true
            }
        }
        DisposableEffect(window) {
            val w = window
            val listener = object : WindowAdapter() {
                override fun windowDeactivated(e: WindowEvent) {
                    if (allowDismiss.value) onDismiss()
                }
            }
            w.addWindowListener(listener)
            onDispose { w.removeWindowListener(listener) }
        }
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesignColors.SurfaceElevated,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .wrapContentSize()
                    .onSizeChanged {
                        with(density) {
                            menuW.intValue = it.width.toDp().value.toInt()
                            menuH.intValue = it.height.toDp().value.toInt()
                        }
                        place()
                    },
            ) {
                Column(
                    Modifier.widthIn(min = 180.dp, max = 280.dp),
                    content = content,
                )
            }
        }
    }
}
