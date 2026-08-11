package io.ymusic.ui

import io.ymusic.settings.StringBackedSettingsStore
import platform.Foundation.NSUserDefaults

internal class IosSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : StringBackedSettingsStore() {
    init {
        seed(
            Keys.mapNotNull { key ->
                defaults.stringForKey(key)?.let { key to it }
            }.toMap(),
        )
    }

    override suspend fun persist() {
        snapshot().forEach { (key, value) -> defaults.setObject(value, forKey = key) }
    }

    private companion object {
        val Keys = listOf(
            "app.hl",
            "app.gl",
            "theme.accent",
            "app.uiLanguage",
            "download.location",
            "download.captionLanguages",
            "download.captionDefault",
            PlaybackQualitySettingKey,
        )
    }
}
