package io.ymusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.FrameWindowScope

/**
 * Keep the AWT window's own background in step with the app's ground.
 *
 * The frame and its content pane are painted by Swing, not by Compose, and show through wherever
 * Compose has not drawn yet — during a resize, on an expose, and while a live theme change settles.
 * Both are set, per JetBrains/compose-multiplatform#1794.
 *
 * This does NOT remove the flash when a window first opens. That comes from below AWT: Compose's
 * Skia layer is a non-opaque component, so Swing never fills it and the native surface shows until
 * Skia presents its first frame. Established by testing plain Swing windows — no flash, with or
 * without a heavyweight canvas — against a Compose window, which flashes; and the flash survives
 * `-Dskiko.renderApi=SOFTWARE`, so the GPU path is not responsible either.
 */
@Composable
fun FrameWindowScope.paintWindowGround(color: Color) {
    val awt = remember(color) { java.awt.Color(color.toArgb(), true) }
    SideEffect {
        if (window.background != awt) {
            window.background = awt
            window.contentPane.background = awt
        }
    }
}

/** The ground a window should wear, for a given appearance. */
fun groundColorFor(dark: Boolean): Color =
    if (dark) io.ymusic.ui.DarkDesignColors.Surface else io.ymusic.ui.LightDesignColors.SurfaceElevated
