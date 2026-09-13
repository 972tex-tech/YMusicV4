package io.ymusic.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** True where the primary pointer is a finger (Android/iOS). Mouse-driven platforms disable
 *  touch idioms like edge-swipe — Compose's mouse drag slop is ~0.1dp, so a whole-screen drag
 *  gesture would steal any click whose press wanders a pixel. */
internal expect val platformUsesTouch: Boolean

/**
 * Hosts captions and transport on the video. On desktop the JAWT overlay covers Skia, so
 * this is a transparent child window above that view; elsewhere it is an in-place Box.
 */
@Composable
internal expect fun VideoChromeLayer(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
)

/** The system back gesture/button (Android's BackHandler): while [enabled], [onBack] intercepts
 *  it instead of finishing the app. No-op on platforms without a system back concept. */
@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Keeps OS chrome legible against the active theme — on Android, flips the status/navigation
 *  bar icon appearance to contrast the edge-to-edge app background. No-op elsewhere. */
@Composable
internal expect fun SyncSystemBars(dark: Boolean)
