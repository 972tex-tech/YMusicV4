package ymusic.ui

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

/**
 * Points the platform's default locale at [tag] (null = restore the system locale), which is where
 * compose-resources resolves `stringResource` from. The caller must rebuild the composition (a
 * `key(tag)` around the app root) — the default locale is not snapshot state.
 */
internal expect fun applyUiLocale(tag: String?)

/** The device's own UI language subtag ("vi"), unaffected by [applyUiLocale] overrides. */
internal expect fun systemLanguageTag(): String

/**
 * The app language for an unseeded install: the device language when we ship its translation, else
 * English (the base). Resolved and PERSISTED once on first launch — the app language is a concrete
 * tag from then on (it doubles as the content language hl), and later device-language changes are
 * deliberately ignored.
 */
internal fun defaultUiLanguageTag(): String {
    val device = systemLanguageTag().substringBefore('-').substringBefore('_').lowercase()
    return UiLanguages.firstOrNull { it.tag == device }?.tag ?: "en"
}

/** Right-to-left scripts by primary subtag — the fixed ISO set, no platform lookup needed. */
private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "ug", "ps", "sd", "yi", "dv", "ckb")

internal fun isRtlLanguage(languageTag: String): Boolean =
    languageTag.substringBefore('-').substringBefore('_').lowercase() in RTL_LANGUAGES
