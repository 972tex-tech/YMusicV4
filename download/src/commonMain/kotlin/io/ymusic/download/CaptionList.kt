package io.ymusic.download

import io.ymusic.ytmedia.sabr.CaptionTrack
import kotlinx.serialization.json.JsonObject
import projectcommon.json.boolOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull

/** One downloadable caption: a video's own track, or one of YouTube's auto-translations of it. */
internal class CaptionEntry(
    val languageCode: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean,
    val isAsr: Boolean,
    val isTranslated: Boolean,
) {
    val key: String get() = captionKey(languageCode, isAsr)

    fun toTrack(): CaptionTrack = CaptionTrack(languageCode, name, baseUrl, isDefault, isAsr)
}

/**
 * The captions a download can pick, one per language code: an uploaded track wins over the
 * auto-generated one, and every other `translationLanguages` entry becomes an auto-translation (`tlang`)
 * of the source track — the first uploaded one, else the auto-generated one, as the player picks it.
 * Reads the download JSON's flattened `captionTracks` / `translationLanguages`.
 */
internal fun captionEntries(data: JsonObject): List<CaptionEntry> {
    val own = data.objects("captionTracks").mapNotNull { track ->
        val url = track.stringOrNull("baseUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val lang = track.stringOrNull("languageCode") ?: return@mapNotNull null
        CaptionEntry(
            languageCode = lang,
            name = track.stringOrNull("name") ?: lang,
            baseUrl = url,
            isDefault = track.boolOrNull("isDefault") ?: false,
            isAsr = track.boolOrNull("isAsr") ?: false,
            isTranslated = false,
        )
    }
    val picked = (own.filter { !it.isAsr } + own.filter { it.isAsr }).distinctBy { it.languageCode }
    val source = own.firstOrNull { !it.isAsr } ?: own.firstOrNull() ?: return picked
    val taken = picked.map { it.languageCode }.toMutableSet()
    val translated = data.objects("translationLanguages").mapNotNull { language ->
        val lang = language.stringOrNull("languageCode") ?: return@mapNotNull null
        if (!taken.add(lang)) return@mapNotNull null
        CaptionEntry(
            languageCode = lang,
            name = language.stringOrNull("name") ?: lang,
            baseUrl = source.baseUrl + (if ('?' in source.baseUrl) "&" else "?") + "tlang=" + lang,
            isDefault = false,
            isAsr = false,
            isTranslated = true,
        )
    }
    return picked + translated
}
