package io.ymusic.ui

import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow

/** The user's chosen download-location folder uri (`file://` on desktop, SAF `content://` on Android), or
 *  null when unset — the headless download service's `downloadFolder` provider. */
fun SettingsStore.downloadLocationFolder(): String? =
    flow(AppSettings.downloadLocation).value.takeIf { it.isNotBlank() }
