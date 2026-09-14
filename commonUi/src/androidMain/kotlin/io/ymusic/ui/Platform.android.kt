package io.ymusic.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val platformUsesTouch: Boolean = true

@Composable
internal actual fun VideoChromeLayer(
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier, content = content)
}

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
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
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) {
            context = context.baseContext
        }
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
}
