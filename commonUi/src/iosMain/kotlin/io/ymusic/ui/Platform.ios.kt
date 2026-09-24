package io.ymusic.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
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
internal actual fun SyncImmersiveSystemBars(immersive: Boolean) {
    val vc = androidx.compose.ui.uikit.LocalUIViewController.current
    androidx.compose.runtime.DisposableEffect(immersive) {
        platform.UIKit.UIApplication.sharedApplication.setStatusBarHidden(immersive, true)
        vc.setNeedsStatusBarAppearanceUpdate()
        onDispose {
            platform.UIKit.UIApplication.sharedApplication.setStatusBarHidden(false, true)
            vc.setNeedsStatusBarAppearanceUpdate()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun Modifier.keepBelowStatusBar(): Modifier =
    windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
