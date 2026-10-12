package io.ymusic.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Initialized on the first applyUiLocale call, before any override lands.
private val systemDefault: Locale = Locale.getDefault()

internal actual fun applyUiLocale(tag: String?) {
    // compose-resources resolves from the JVM default locale, so this is enough for the shared UI.
    // Framework-side integration (LocaleManager per-app locales on API 33+) can layer on later.
    Locale.setDefault(tag?.let(Locale::forLanguageTag) ?: systemDefault)
}

internal actual fun systemLanguageTag(): String = systemDefault.language

internal actual fun systemLocaleTag(): String = systemDefault.toLanguageTag()

internal actual fun formatTimeOfDay(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))
