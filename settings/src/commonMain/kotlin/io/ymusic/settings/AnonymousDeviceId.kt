package io.ymusic.settings

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The stable anonymous id for telemetry and API calls: a random UUID minted on first launch and
 * persisted under [AppSettings.deviceId]. Deliberately NOT device identity (no ANDROID_ID, no
 * machine guid) — clearing app data mints a new one, and nothing about it is correlatable with the
 * hardware or other apps.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun SettingsStore.anonymousDeviceId(): String {
    val saved = flow(AppSettings.deviceId).value
    if (saved.isNotEmpty() && runCatching { Uuid.parse(saved) }.isSuccess) return saved
    // Non-empty but not a uuid: a legacy hardware-derived id (ANDROID_ID / machine hash).
    // Replacing it is the migration AND the purge — the service only accepts uuids.
    return Uuid.random().toString().also { put(AppSettings.deviceId, it) }
}
