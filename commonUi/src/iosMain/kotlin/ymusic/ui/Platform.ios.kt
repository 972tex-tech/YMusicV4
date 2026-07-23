package ymusic.ui

import androidx.compose.runtime.Composable

internal actual val platformUsesTouch: Boolean = true

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; the edge-swipe gesture can be wired here later.
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // The status bar rides the hosting UIViewController; Compose already draws edge-to-edge
    // under it, and its text style can be wired through MainViewController when needed.
}
