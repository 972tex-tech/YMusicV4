package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
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
    var x by remember { mutableIntStateOf(0) }
    var y by remember { mutableIntStateOf(0) }
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
                x = (loc?.x ?: 0) + p.x.roundToInt()
                y = (loc?.y ?: 0) + p.y.roundToInt() + c.size.height
            },
    )
    if (!expanded) return
    val state = rememberDialogState(
        position = WindowPosition(x.dp, y.dp),
        size = DpSize(240.dp, 320.dp),
    )
    state.position = WindowPosition(x.dp, y.dp)
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
        DisposableEffect(window) {
            val w = window
            val listener = object : WindowAdapter() {
                override fun windowDeactivated(e: WindowEvent) {
                    onDismiss()
                }
            }
            w.addWindowListener(listener)
            onDispose { w.removeWindowListener(listener) }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DesignColors.SurfaceElevated,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier
                    .widthIn(min = 180.dp, max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}
