package ymusic.ui

import org.jetbrains.compose.resources.StringResource
import ymusic.ui.resources.Res
import ymusic.ui.resources.settings_language_system

/**
 * A language the in-app picker offers. [nativeName] is the language's own name for itself — it must
 * read correctly whatever locale the UI currently shows, so it is not a translated resource.
 */
class UiLanguage(val tag: String, val nativeName: String)

/** The locales that actually have a `composeResources/values-*` translation, plus the base. */
val UiLanguages: List<UiLanguage> = listOf(
    UiLanguage("en", "English"),
    UiLanguage("vi", "Tiếng Việt"),
    UiLanguage("ar", "العربية"),
)

val UiLanguageSystem: StringResource get() = Res.string.settings_language_system

/**
 * Points the platform's default locale at [tag] (null = restore the system locale), which is where
 * compose-resources resolves `stringResource` from. The caller must rebuild the composition (a
 * `key(tag)` around the app root) — the default locale is not snapshot state.
 */
internal expect fun applyUiLocale(tag: String?)

/** The device's own UI language subtag ("vi"), unaffected by [applyUiLocale] overrides. */
internal expect fun systemLanguageTag(): String

/**
 * The language the app is actually showing for the [override] setting ("" = system): the override,
 * else the device language when we ship its translation, else English (the base). This is ALSO the
 * content language — the app has one language, and picking it reports hl to the engine.
 */
internal fun resolvedUiLanguageTag(override: String): String {
    if (override.isNotBlank()) return override
    val device = systemLanguageTag().substringBefore('-').substringBefore('_').lowercase()
    return UiLanguages.firstOrNull { it.tag == device }?.tag ?: "en"
}

/** Right-to-left scripts by primary subtag — the fixed ISO set, no platform lookup needed. */
private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ug", "ps", "sd", "yi", "dv", "ckb")

internal fun isRtlLanguage(languageTag: String): Boolean =
    languageTag.substringBefore('-').substringBefore('_').lowercase() in RTL_LANGUAGES
