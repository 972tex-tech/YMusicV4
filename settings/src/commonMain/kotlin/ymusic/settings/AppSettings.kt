package ymusic.settings

import kotlinx.coroutines.flow.StateFlow

data class StringSetting(val key: String, val default: String)

fun SettingsStore.flow(setting: StringSetting): StateFlow<String> = string(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: StringSetting,
    value: String,
) = putString(setting.key, value)

object AppSettings {
    /** InnerTube `hl` — content language (BCP-47); empty = follow the device locale. */
    val hl = StringSetting("app.hl", "")

    /** InnerTube `gl` — content region (ISO 3166-1 alpha-2); empty = follow [glDetected]. */
    val gl = StringSetting("app.gl", "")

    /** The region YouTube itself last reported for this connection; empty = never asked. Not a
     *  user preference — the app writes it, [gl] overrides it. */
    val glDetected = StringSetting("app.gl.detected", "")

    /** Theme accent placeholder; empty = platform default accent. */
    val themeAccent = StringSetting("theme.accent", "")

    /** UI language override (BCP-47); empty = follow the system locale. */
    val uiLanguage = StringSetting("app.uiLanguage", "")

    /** Theme override: "light" | "dark"; empty = follow the system appearance. */
    val theme = StringSetting("app.theme", "")
}
