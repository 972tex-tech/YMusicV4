package ymusic.ui

import ymusic.download.CaptionPreference
import ymusic.settings.AppSettings
import ymusic.settings.SettingsStore
import ymusic.settings.flow

/**
 * The current caption download preference from settings, as the headless download service consumes it.
 * Read fresh at download time by the shells (see `DownloadService.captionPreference`), so it always
 * reflects the latest [AppSettings.preferredCaptionLanguages] / [AppSettings.downloadDefaultCaption].
 */
fun SettingsStore.downloadCaptionPreference(): CaptionPreference = CaptionPreference(
    languages = flow(AppSettings.preferredCaptionLanguages).value,
    includeDefault = flow(AppSettings.downloadDefaultCaption).value,
)
