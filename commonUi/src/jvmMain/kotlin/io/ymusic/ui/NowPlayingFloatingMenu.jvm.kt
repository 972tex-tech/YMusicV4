package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
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
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

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
                val owner = Frame.getFrames().firstOrNull { it.isShowing && it.width > 64 }
                val loc = try {
                    owner?.locationOnScreen
                } catch (_: Exception) {
                    null
                }
                val p = c.positionInWindow()
                val left = (loc?.x ?: 0) + p.x.roundToInt()
                anchorRight.intValue = left + c.size.width
                anchorBottom.intValue = (loc?.y ?: 0) + p.y.roundToInt() + c.size.height
            },
    )
    if (!expanded) return
    val state = rememberDialogState(
        position = WindowPosition(0.dp, 0.dp),
        size = DpSize(280.dp, 800.dp),
    )
    LaunchedEffect(anchorRight.intValue, anchorBottom.intValue, menuW.intValue, menuH.intValue) {
        val w = menuW.intValue
        val h = menuH.intValue
        if (w > 0 && h > 0) {
            state.size = DpSize(with(density) { w.toDp() }, with(density) { h.toDp() })
            state.position = WindowPosition(
                (anchorRight.intValue - w).coerceAtLeast(0).dp,
                anchorBottom.intValue.dp,
            )
        }
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
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DesignColors.SurfaceElevated,
            shadowElevation = 8.dp,
            modifier = Modifier
                .wrapContentWidth()
                .wrapContentHeight()
                .onSizeChanged {
                    menuW.intValue = it.width
                    menuH.intValue = it.height
                    if (it.width > 0 && it.height > 0) {
                        state.size = DpSize(
                            with(density) { it.width.toDp() },
                            with(density) { it.height.toDp() },
                        )
                        state.position = WindowPosition(
                            (anchorRight.intValue - it.width).coerceAtLeast(0).dp,
                            anchorBottom.intValue.dp,
                        )
                    }
                },
        ) {
            Column(
                Modifier.widthIn(min = 180.dp, max = 280.dp),
                content = content,
            )
        }
    }
}
