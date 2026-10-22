package io.ymusic.ios

import io.ymusic.settings.StringBackedSettingsStore
import io.ymusic.ui.PlaybackAudioOnlyKey
import io.ymusic.ui.PlaybackEqKey
import io.ymusic.ui.PlaybackLoopModeKey
import io.ymusic.ui.PlaybackPitchKey
import io.ymusic.ui.PlaybackQualitySettingKey
import io.ymusic.ui.PlaybackShuffleKey
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

    override suspend fun persist(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    private companion object {
        val Keys = listOf(
            "app.hl",
            "app.gl",
            "theme.accent",
            "app.uiLanguage",
            "app.lastSection",
            "app.lastDiscoverTab",
            "download.location",
            "download.quickPreset",
            "playerCache.maxMb",
            "telemetry.lastLoadedEngineJsVer",
            "telemetry.lastLoadedPlayerJsVer",
            "update.lastRunVersionCode",
            PlaybackQualitySettingKey,
            PlaybackAudioOnlyKey,
            PlaybackVolumeKey,
            PlaybackSpeedKey,
            PlaybackPitchKey,
            PlaybackEqKey,
            PlaybackShuffleKey,
            PlaybackLoopModeKey,
        )
    }
}
