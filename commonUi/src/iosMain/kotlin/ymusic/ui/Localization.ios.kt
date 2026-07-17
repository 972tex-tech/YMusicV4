package ymusic.ui

import platform.Foundation.NSUserDefaults

internal actual fun applyUiLocale(tag: String?) {
    // NSLocale's preferred languages are fixed per-process — the override lands on next launch.
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) {
        defaults.removeObjectForKey("AppleLanguages")
    } else {
        defaults.setObject(listOf(tag), forKey = "AppleLanguages")
    }
}
