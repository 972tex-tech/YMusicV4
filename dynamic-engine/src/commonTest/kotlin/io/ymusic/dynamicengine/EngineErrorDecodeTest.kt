package io.ymusic.dynamicengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The Kotlin half of the error contract: [decodeEngineError] rebuilds a pipe RESET reason into a
 *  typed [EngineError] — categories to subclasses, site codes to [EngineError.Technical], an unknown
 *  category to [EngineError.Other], and a non-structured reason salvaged or fallen back to E20003. */
class EngineErrorDecodeTest {

    @Test
    fun network_category_carries_message_and_js_fields() {
        val e = decodeEngineError(
            """{"code":"network","message":"connect refused","name":"NetworkError","stack":"at x"}""",
            cause = null,
        )
        assertIs<EngineError.Network>(e)
        assertEquals("network", e.code)
        assertEquals("connect refused", e.message)
        assertEquals("NetworkError", e.jsName)
        assertEquals("at x", e.jsStack)
    }

    @Test
    fun parse_and_oom_categories_map_to_their_subclasses() {
        assertIs<EngineError.Parse>(decodeEngineError("""{"code":"parse","message":"bad shape"}""", null))
        assertIs<EngineError.Oom>(decodeEngineError("""{"code":"oom","message":"out of memory"}""", null))
    }

    @Test
    fun site_code_becomes_technical_and_keeps_user_message() {
        val e = decodeEngineError("""{"code":"E29999","message":"shelf shape","userMessage":"Try again"}""", null)
        assertIs<EngineError.Technical>(e)
        assertEquals("E29999", e.code)
        assertEquals("Try again", e.userMessage)
    }

    @Test
    fun unknown_category_degrades_to_other_with_the_code_intact() {
        val e = decodeEngineError("""{"code":"region_blocked","message":"geo"}""", null)
        assertIs<EngineError.Other>(e)
        assertEquals("region_blocked", e.code)
        assertNull(e.userMessage)
    }

    @Test
    fun non_structured_reason_salvages_an_embedded_site_code() {
        val e = decodeEngineError("E20010 unexpected data on the control pipe", null)
        assertIs<EngineError.Technical>(e)
        assertEquals("E20010", e.code)
        assertEquals("E20010 unexpected data on the control pipe", e.message)
    }

    @Test
    fun unstructured_reason_falls_back_to_the_decoder_code() {
        val e = decodeEngineError("something entirely unstructured", null)
        assertIs<EngineError.Technical>(e)
        assertEquals("E20003", e.code)
    }
}
