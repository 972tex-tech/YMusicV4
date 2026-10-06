package io.ymusic.download

import io.ymusic.ytmedia.sabr.CaptionTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val own = (data["captionTracks"] as? JsonArray).orEmpty().mapNotNull { element ->
        val track = element as? JsonObject ?: return@mapNotNull null
        val url = track.str("baseUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val lang = track.str("languageCode") ?: return@mapNotNull null
        CaptionEntry(
            languageCode = lang,
            name = track.str("name") ?: lang,
            baseUrl = url,
            isDefault = track.str("isDefault")?.toBoolean() ?: false,
            isAsr = track.str("isAsr")?.toBoolean() ?: false,
            isTranslated = false,
        )
    }
    val picked = (own.filter { !it.isAsr } + own.filter { it.isAsr }).distinctBy { it.languageCode }
    val source = own.firstOrNull { !it.isAsr } ?: own.firstOrNull() ?: return picked
    val taken = picked.map { it.languageCode }.toMutableSet()
    val translated = (data["translationLanguages"] as? JsonArray).orEmpty().mapNotNull { element ->
        val lang = (element as? JsonObject)?.str("languageCode") ?: return@mapNotNull null
        if (!taken.add(lang)) return@mapNotNull null
        CaptionEntry(
            languageCode = lang,
            name = element.str("name") ?: lang,
            baseUrl = source.baseUrl + (if ('?' in source.baseUrl) "&" else "?") + "tlang=" + lang,
            isDefault = false,
            isAsr = false,
            isTranslated = true,
        )
    }
    return picked + translated
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
