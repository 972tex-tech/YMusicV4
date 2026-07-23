package ymusic.ui

import androidx.compose.runtime.Composable

/** True where the primary pointer is a finger (Android/iOS). Mouse-driven platforms disable
 *  touch idioms like edge-swipe — Compose's mouse drag slop is ~0.1dp, so a whole-screen drag
 *  gesture would steal any click whose press wanders a pixel. */
internal expect val platformUsesTouch: Boolean

/** The system back gesture/button (Android's BackHandler): while [enabled], [onBack] intercepts
 *  it instead of finishing the app. No-op on platforms without a system back concept. */
@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Keeps OS chrome legible against the active theme — on Android, flips the status/navigation
 *  bar icon appearance to contrast the edge-to-edge app background. No-op elsewhere. */
@Composable
internal expect fun SyncSystemBars(dark: Boolean)
