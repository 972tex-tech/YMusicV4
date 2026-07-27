package ymusic.settings

import kotlinx.coroutines.flow.StateFlow

data class StringSetting(val key: String, val default: String)

fun SettingsStore.flow(setting: StringSetting): StateFlow<String> = string(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: StringSetting,
    value: String,
) = putString(setting.key, value)

/**
 * The app language, seeded on first ask: a blank read resolves [default], PERSISTS it, and returns
 * it — from then on the key is never blank and is the single source of truth for every reader (the
 * UI and the engine's settings pull alike). Shells call this once before starting the engine.
 */
suspend fun SettingsStore.ensureUiLanguage(default: () -> String): String {
    val held = string(AppSettings.uiLanguage.key, "").value
    if (held.isNotBlank()) return held
    val resolved = default()
    putString(AppSettings.uiLanguage.key, resolved)
    return resolved
}

object AppSettings {
    // The CONTENT locale (YouTube hl/gl) is deliberately absent: it lives in the engine
    // (yt/locale/apply + the engine's own ladder), not in app settings.

    /** Theme accent placeholder; empty = platform default accent. */
    val themeAccent = StringSetting("theme.accent", "")

    /** UI language override (BCP-47); empty = follow the system locale. */
    val uiLanguage = StringSetting("app.uiLanguage", "")

    /** Theme override: "light" | "dark"; empty = follow the system appearance. */
    val theme = StringSetting("app.theme", "")
}
