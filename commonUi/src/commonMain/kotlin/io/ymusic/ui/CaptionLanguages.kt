package io.ymusic.ui

/**
 * A caption language offered by the download settings' language picker. [code] is the two-letter
 * ISO-639-1 tag YouTube keys caption tracks on (`en`, `vi`, …); [name] is its English name, which is
 * what the autocomplete matches against alongside the code.
 */
class CaptionLanguage(val code: String, val name: String)

/** Common caption languages — a fixed, hardcoded catalogue (YouTube caption tracks use 2-letter codes). */
val CaptionLanguages: List<CaptionLanguage> = listOf(
    CaptionLanguage("en", "English"),
    CaptionLanguage("es", "Spanish"),
    CaptionLanguage("pt", "Portuguese"),
    CaptionLanguage("fr", "French"),
    CaptionLanguage("de", "German"),
    CaptionLanguage("it", "Italian"),
    CaptionLanguage("nl", "Dutch"),
    CaptionLanguage("ru", "Russian"),
    CaptionLanguage("uk", "Ukrainian"),
    CaptionLanguage("pl", "Polish"),
    CaptionLanguage("cs", "Czech"),
    CaptionLanguage("ro", "Romanian"),
    CaptionLanguage("el", "Greek"),
    CaptionLanguage("tr", "Turkish"),
    CaptionLanguage("ar", "Arabic"),
    CaptionLanguage("he", "Hebrew"),
    CaptionLanguage("fa", "Persian"),
    CaptionLanguage("hi", "Hindi"),
    CaptionLanguage("bn", "Bengali"),
    CaptionLanguage("ta", "Tamil"),
    CaptionLanguage("te", "Telugu"),
    CaptionLanguage("ur", "Urdu"),
    CaptionLanguage("th", "Thai"),
    CaptionLanguage("vi", "Vietnamese"),
    CaptionLanguage("id", "Indonesian"),
    CaptionLanguage("ms", "Malay"),
    CaptionLanguage("ja", "Japanese"),
    CaptionLanguage("ko", "Korean"),
    CaptionLanguage("zh", "Chinese"),
    CaptionLanguage("sv", "Swedish"),
    CaptionLanguage("da", "Danish"),
    CaptionLanguage("nb", "Norwegian"),
    CaptionLanguage("fi", "Finnish"),
    CaptionLanguage("hu", "Hungarian"),
    CaptionLanguage("sk", "Slovak"),
    CaptionLanguage("bg", "Bulgarian"),
    CaptionLanguage("hr", "Croatian"),
    CaptionLanguage("sr", "Serbian"),
    CaptionLanguage("sl", "Slovenian"),
    CaptionLanguage("et", "Estonian"),
    CaptionLanguage("lv", "Latvian"),
    CaptionLanguage("lt", "Lithuanian"),
    CaptionLanguage("ca", "Catalan"),
    CaptionLanguage("sw", "Swahili"),
    CaptionLanguage("af", "Afrikaans"),
    CaptionLanguage("fil", "Filipino"),
)

/** Display name for a stored caption code, falling back to the raw code for anything off-catalogue. */
internal fun captionLanguageName(code: String): String =
    CaptionLanguages.firstOrNull { it.code == code }?.name ?: code
