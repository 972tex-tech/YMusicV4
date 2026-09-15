package io.ymusic.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val platformUsesTouch: Boolean = true

internal actual val desktopLayoutHole: Boolean = false

@Composable
internal actual fun VideoChromeLayer(
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier, content = content)
}

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; the edge-swipe gesture can be wired here later.
}

@Composable
internal actual fun BoxScope.NowPlayingFloatingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // The status bar rides the hosting UIViewController; Compose already draws edge-to-edge
    // under it, and its text style can be wired through MainViewController when needed.
}
