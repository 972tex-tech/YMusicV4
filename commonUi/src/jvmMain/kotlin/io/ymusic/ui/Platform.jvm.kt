package io.ymusic.ui

import androidx.compose.runtime.Composable

internal actual val platformUsesTouch: Boolean = false

internal actual val playerSurfaceCoversCompose: Boolean = true

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back concept on desktop; navigation uses the in-app back arrows.
}

@Composable
internal actual fun SyncSystemBars(dark: Boolean) {
    // Desktop windows carry no app-tinted OS chrome.
}
