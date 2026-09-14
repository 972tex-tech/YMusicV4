package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    var anchorRight by remember { mutableIntStateOf(0) }
    var anchorBottom by remember { mutableIntStateOf(0) }
    var menuW by remember { mutableIntStateOf(0) }
    var menuH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
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
                anchorRight = left + c.size.width
                anchorBottom = (loc?.y ?: 0) + p.y.roundToInt() + c.size.height
            },
    )
    if (!expanded) return
    val state = rememberDialogState(
        position = WindowPosition(anchorRight.dp, anchorBottom.dp),
        size = DpSize(240.dp, 480.dp),
    )
    SideEffect {
        val widthPx = if (menuW > 0) menuW else with(density) { 240.dp.roundToPx() }
        state.position = WindowPosition((anchorRight - widthPx).coerceAtLeast(0).dp, anchorBottom.dp)
        if (menuW > 0 && menuH > 0) {
            state.size = DpSize(with(density) { menuW.toDp() }, with(density) { menuH.toDp() })
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
        LaunchedEffect(menuW, menuH) {
            if (menuW > 0 && menuH > 0) {
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
                .wrapContentHeight()
                .onSizeChanged {
                    menuW = it.width
                    menuH = it.height
                },
        ) {
            Column(
                Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}
