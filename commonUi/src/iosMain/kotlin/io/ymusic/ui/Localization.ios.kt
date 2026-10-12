package io.ymusic.ui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier

internal actual fun applyUiLocale(tag: String?) {
    // NSLocale's preferred languages are fixed per-process — the override lands on next launch.
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) {
        defaults.removeObjectForKey("AppleLanguages")
    } else {
        // The app's tags are YouTube's hl codes; iOS wants the modern Hebrew code.
        defaults.setObject(listOf(if (tag == "iw") "he" else tag), forKey = "AppleLanguages")
    }
}

internal actual fun systemLanguageTag(): String {
    val identifier = NSLocale.currentLocale.localeIdentifier.replace('-', '_')
    return identifier.substringBefore('@').substringBefore('_')
}

internal actual fun systemLocaleTag(): String =
    NSLocale.currentLocale.localeIdentifier.substringBefore('@').replace('_', '-')

internal actual fun formatTimeOfDay(epochMs: Long): String {
    val formatter = NSDateFormatter()
    formatter.dateStyle = NSDateFormatterNoStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}
