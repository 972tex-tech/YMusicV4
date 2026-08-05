package ymusic.download

import ymusic.ytmedia.sabr.CaptionTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptionSelectionTest {

    private fun track(lang: String, default: Boolean = false, asr: Boolean = false) =
        CaptionTrack(languageCode = lang, name = lang.uppercase(), baseUrl = "https://x/$lang?pot=t", isDefault = default, isAsr = asr)

    private val tracks = listOf(
        track("en", default = true),
        track("es"),
        track("pt-BR"),
        track("fr", asr = true),
    )

    @Test
    fun exact_language_match_is_selected() {
        val picked = selectCaptions(tracks, CaptionPreference(setOf("es"), includeDefault = false))
        assertEquals(listOf("es"), picked.map { it.languageCode })
    }

    @Test
    fun base_language_matches_regional_variant() {
        // "pt" preference matches the "pt-BR" track.
        val picked = selectCaptions(tracks, CaptionPreference(setOf("pt"), includeDefault = false))
        assertEquals(listOf("pt-BR"), picked.map { it.languageCode })
    }

    @Test
    fun multiple_preferences_select_all_present() {
        val picked = selectCaptions(tracks, CaptionPreference(setOf("en", "es", "de"), includeDefault = false))
        assertEquals(listOf("en", "es"), picked.map { it.languageCode }) // "de" absent → skipped
    }

    @Test
    fun no_match_without_default_selects_nothing() {
        val picked = selectCaptions(tracks, CaptionPreference(setOf("de"), includeDefault = false))
        assertEquals(emptyList(), picked.map { it.languageCode })
    }

    @Test
    fun no_match_with_default_falls_back_to_the_default_track() {
        val picked = selectCaptions(tracks, CaptionPreference(setOf("de"), includeDefault = true))
        assertEquals(listOf("en"), picked.map { it.languageCode }) // "en" is the default track
    }

    @Test
    fun default_fallback_prefers_non_asr_when_no_flagged_default() {
        val noDefault = listOf(track("fr", asr = true), track("es"))
        val picked = selectCaptions(noDefault, CaptionPreference(emptySet(), includeDefault = true))
        assertEquals(listOf("es"), picked.map { it.languageCode }) // skips the ASR track
    }

    @Test
    fun empty_track_list_is_empty() {
        assertEquals(emptyList(), selectCaptions(emptyList(), CaptionPreference(setOf("en"), includeDefault = true)))
    }

    @Test
    fun mp3_downloads_no_captions() {
        // MP3 can't carry a subtitle track → skip caption download regardless of preference.
        val pref = CaptionPreference(setOf("en"), includeDefault = true)
        assertEquals(emptyList(), captionsToDownload(DownloadFormat.MP3, tracks, pref))
    }

    @Test
    fun non_mp3_formats_select_captions_normally() {
        val pref = CaptionPreference(setOf("en"), includeDefault = false)
        assertEquals(listOf("en"), captionsToDownload(DownloadFormat.M4A, tracks, pref).map { it.languageCode })
        assertEquals(listOf("en"), captionsToDownload(DownloadFormat.VIDEO_MP4, tracks, pref).map { it.languageCode })
    }
}
