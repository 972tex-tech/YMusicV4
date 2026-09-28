package io.ymusic.ios

import io.ymusic.settings.StringBackedSettingsStore
import io.ymusic.ui.PlaybackEqKey
import io.ymusic.ui.PlaybackPitchKey
import io.ymusic.ui.PlaybackQualitySettingKey
import io.ymusic.ui.PlaybackSpeedKey
import io.ymusic.ui.PlaybackVolumeKey
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
            PlaybackVolumeKey,
            PlaybackSpeedKey,
            PlaybackPitchKey,
            PlaybackEqKey,
        )
    }
}
