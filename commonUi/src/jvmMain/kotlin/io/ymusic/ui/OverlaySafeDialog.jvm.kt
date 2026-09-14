package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import java.awt.GraphicsEnvironment

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun OverlaySafeDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (GraphicsEnvironment.isHeadless()) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, content = content)
        return
    }
    val owner = chromeOwner()
    val loc = try {
        owner?.locationOnScreen
    } catch (_: Exception) {
        null
    }
    // AWT screen coords are points, same unit Compose uses for .dp on desktop.
    val state = rememberDialogState(
        position = if (loc != null) {
            WindowPosition(loc.x.dp, loc.y.dp)
        } else {
            WindowPosition.PlatformDefault
        },
        size = if (owner != null) {
            DpSize(owner.width.dp, owner.height.dp)
        } else {
            DpSize(480.dp, 360.dp)
        },
    )
    DialogWindow(
        onCloseRequest = onDismissRequest,
        state = state,
        title = "",
        decoration = WindowDecoration.Undecorated(),
        transparent = true,
        resizable = false,
        focusable = true,
        alwaysOnTop = false,
        modalityType = DialogModalityType.DocumentModal,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            ) {
                content()
            }
        }
    }
}
