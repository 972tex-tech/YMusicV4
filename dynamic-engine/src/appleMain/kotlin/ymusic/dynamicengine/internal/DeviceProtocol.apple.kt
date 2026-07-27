package ymusic.dynamicengine.internal

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

internal actual fun deviceLocale(): Pair<String, String> {
    val locale = NSLocale.currentLocale
    val identifier = locale.localeIdentifier.replace('-', '_')
    val language = identifier.substringBefore('@').substringBefore('_')
    val region = locale.countryCode ?: identifier.substringAfter('_', "").substringBefore('@')
    return language.ifBlank { "en" } to region.ifBlank { "US" }
}
