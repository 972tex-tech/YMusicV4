package ymusic.settings

import kotlinx.coroutines.flow.StateFlow

data class StringSetting(val key: String, val default: String)

fun SettingsStore.flow(setting: StringSetting): StateFlow<String> = string(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: StringSetting,
    value: String,
) = putString(setting.key, value)

object AppSettings {
    /** InnerTube `hl` — UI/content language (BCP-47). */
    val hl = StringSetting("app.hl", "en")

    /** InnerTube `gl` — content region (ISO 3166-1 alpha-2). */
    val gl = StringSetting("app.gl", "US")

    /** Theme accent placeholder; empty = platform default accent. */
    val themeAccent = StringSetting("theme.accent", "")

    /** UI language override (BCP-47); empty = follow the system locale. */
    val uiLanguage = StringSetting("app.uiLanguage", "")
}
