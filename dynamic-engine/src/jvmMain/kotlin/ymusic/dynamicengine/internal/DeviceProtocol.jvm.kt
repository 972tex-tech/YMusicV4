package ymusic.dynamicengine.internal

internal actual fun deviceLocale(): Pair<String, String> {
    val locale = java.util.Locale.getDefault()
    return locale.language.ifBlank { "en" } to locale.country.ifBlank { "US" }
}
