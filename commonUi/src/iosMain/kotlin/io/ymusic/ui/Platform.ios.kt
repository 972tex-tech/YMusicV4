package io.ymusic.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val platformUsesTouch: Boolean = true

internal actual val desktopLayoutHole: Boolean = false

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; the edge-swipe gesture can be wired here later.
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // The status bar rides the hosting UIViewController; Compose already draws edge-to-edge
    // under it, and its text style can be wired through MainViewController when needed.
}

@Composable
internal actual fun SyncSensorRotation(enabled: Boolean) {
    // Info.plist already lists portrait and both landscapes.
}

@Composable
internal actual fun SyncImmersiveSystemBars(immersive: Boolean) {
    // UIApplication.setStatusBarHidden is not in the iOS 26 Kotlin bindings.
}

@Composable
internal actual fun Modifier.keepBelowStatusBar(): Modifier =
    windowInsetsPadding(WindowInsets.statusBars)
