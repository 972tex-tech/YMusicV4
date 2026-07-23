package ymusic.ui

import androidx.compose.runtime.Composable

internal actual val platformUsesTouch: Boolean = true

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
}
