package io.ymusic.ui

import io.ymusic.download.CaptionPreference
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow

/**
 * The current caption download preference from settings, as the headless download service consumes it.
 * Read fresh at download time by the shells (see `DownloadService.captionPreference`), so it always
 * reflects the latest [AppSettings.preferredCaptionLanguages] / [AppSettings.downloadDefaultCaption].
 */
fun SettingsStore.downloadCaptionPreference(): CaptionPreference = CaptionPreference(
    languages = flow(AppSettings.preferredCaptionLanguages).value,
    includeDefault = flow(AppSettings.downloadDefaultCaption).value,
)

/** The user's chosen download-location folder uri (`file://` on desktop, SAF `content://` on Android), or
 *  null when unset — the headless download service's `downloadFolder` provider. */
fun SettingsStore.downloadLocationFolder(): String? =
    flow(AppSettings.downloadLocation).value.takeIf { it.isNotBlank() }
