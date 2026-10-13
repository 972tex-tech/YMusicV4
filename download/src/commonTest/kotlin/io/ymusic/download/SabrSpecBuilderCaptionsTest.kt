package io.ymusic.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player-response → [io.ymusic.ytmedia.sabr.SabrStreamSpec] mapping for captions. Mirrors the flattened
 * `captionTracks` array the MSE player's player response carries (baseUrls already signature-decoded +
 * pot-bound there).
 */
class SabrSpecBuilderCaptionsTest {

    // A minimal SABR player response plus the flattened captionTracks the JS side attaches.
    private fun json(captionTracks: String) = """
        {
          "streamingData": {
            "serverAbrStreamingUrl": "https://sabr.example/videoplayback?x=1",
            "adaptiveFormats": []
          },
          "videoPlaybackUstreamerConfig": "AAAA",
          "captionTracks": $captionTracks
        }
    """.trimIndent()

    @Test
    fun parses_flattened_caption_tracks() {
        val spec = SabrSpecBuilder.build(
            "abc12345678",
            json(
                """[
                  {"languageCode":"en","name":"English","baseUrl":"https://tt/en?pot=T","isAsr":false,"isDefault":true},
                  {"languageCode":"fr","name":"French (auto)","baseUrl":"https://tt/fr?pot=T","isAsr":true,"isDefault":false}
                ]""",
            ),
            poTokenBase64 = null,
        )
        assertEquals(listOf("en", "fr"), spec.captionTracks.map { it.languageCode })
        val en = spec.captionTracks.first()
        assertEquals("English", en.name)
        assertTrue(en.isDefault)
        assertTrue(spec.captionTracks[1].isAsr)
        assertTrue(en.baseUrl.contains("pot=T"), "baseUrl carried through verbatim (cooked by JS)")
    }

    @Test
    fun missing_or_empty_caption_tracks_yield_empty_list() {
        assertEquals(emptyList(), SabrSpecBuilder.build("abc12345678", json("[]"), null).captionTracks)
        // The field absent entirely (older JS payload) also degrades to empty, not a crash.
        val noField = """
            {"streamingData":{"serverAbrStreamingUrl":"https://s/vp?x=1","adaptiveFormats":[]},"videoPlaybackUstreamerConfig":"AAAA"}
        """.trimIndent()
        assertEquals(emptyList(), SabrSpecBuilder.build("abc12345678", noField, null).captionTracks)
    }

    @Test
    fun entries_missing_a_baseUrl_are_dropped() {
        val spec = SabrSpecBuilder.build(
            "abc12345678",
            json(
                """[
                  {"languageCode":"en","name":"English","baseUrl":"https://tt/en?pot=T"},
                  {"languageCode":"de","name":"German"}
                ]""",
            ),
            null,
        )
        assertEquals(listOf("en"), spec.captionTracks.map { it.languageCode })
    }
}
