package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val desktopLayoutHole: Boolean = true

/**
 * In-place overlay. The JAWT canvas lives in a window *behind* the Compose UI (layout hole),
 * so chrome does not need a second DialogWindow.
 */
@Composable
internal actual fun VideoChromeLayer(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, content = content)
}
