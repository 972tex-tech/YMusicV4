package ymusic.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

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

internal actual fun deviceContentLocale(): Pair<String, String> {
    val locale = NSLocale.currentLocale
    val identifier = locale.localeIdentifier.replace('-', '_')
    val language = identifier.substringBefore('@').substringBefore('_')
    val region = locale.countryCode ?: identifier.substringAfter('_', "").substringBefore('@')
    return language.ifBlank { "en" } to region.ifBlank { "US" }
}
