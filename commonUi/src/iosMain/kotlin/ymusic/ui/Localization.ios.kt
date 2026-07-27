package ymusic.ui

import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

internal actual fun applyUiLocale(tag: String?) {
    // NSLocale's preferred languages are fixed per-process — the override lands on next launch.
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) {
        defaults.removeObjectForKey("AppleLanguages")
    } else {
        defaults.setObject(listOf(tag), forKey = "AppleLanguages")
    }
}

internal actual fun systemLanguageTag(): String {
    val identifier = NSLocale.currentLocale.localeIdentifier.replace('-', '_')
    return identifier.substringBefore('@').substringBefore('_')
}
