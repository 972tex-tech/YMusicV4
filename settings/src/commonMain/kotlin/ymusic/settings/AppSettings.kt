package ymusic.settings

import kotlinx.coroutines.flow.StateFlow

data class StringSetting(val key: String, val default: String)

fun SettingsStore.flow(setting: StringSetting): StateFlow<String> = string(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: StringSetting,
    value: String,
) = putString(setting.key, value)

/**
 * A multi-value string setting: a set of values kept in one key, newline-joined (values themselves
 * must not contain '\n' — BCP-47 codes, uris, etc. don't). The decoded view preserves insertion
 * order and drops blanks; an empty key reads as the empty set.
 */
data class StringSetSetting(val key: String, val default: Set<String> = emptySet())

private const val SET_DELIMITER = "\n"

fun SettingsStore.flow(setting: StringSetSetting): StateFlow<Set<String>> =
    MappedStateFlow(string(setting.key, "")) { decodeStringSet(it) }

suspend fun SettingsStore.put(
    setting: StringSetSetting,
    values: Set<String>,
) = putString(setting.key, values.joinToString(SET_DELIMITER))

internal fun decodeStringSet(raw: String): Set<String> =
    raw.split(SET_DELIMITER).filter { it.isNotBlank() }.toCollection(LinkedHashSet())

data class BoolSetting(val key: String, val default: Boolean)

fun SettingsStore.flow(setting: BoolSetting): StateFlow<Boolean> = boolean(setting.key, setting.default)

suspend fun SettingsStore.put(
    setting: BoolSetting,
    value: Boolean,
) = putBoolean(setting.key, value)

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

    /**
     * Where finished downloads are written — a media-store bucket uri (the folder is also a Library
     * bucket, since downloads land inside the local library). Empty = not chosen yet: the download
     * flow asks the user to pick a folder before the first download.
     */
    val downloadLocation = StringSetting("download.location", "")

    /**
     * Caption languages to fetch alongside a downloaded media (BCP-47 codes). Independent of
     * [downloadDefaultCaption]: these are the extra languages to grab when a video offers them.
     */
    val preferredCaptionLanguages = StringSetSetting("download.captionLanguages")

    /** Always fetch the media's own default caption track, on top of any [preferredCaptionLanguages]. */
    val downloadDefaultCaption = BoolSetting("download.captionDefault", true)
}
