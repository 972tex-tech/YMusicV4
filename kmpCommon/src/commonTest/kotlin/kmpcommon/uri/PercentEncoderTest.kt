package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * decode()'s contract: "Invalid percent-encodings are left as-is" and the decoder "will return
 * garbage rather than throw". Truncated multibyte UTF-8 (a lead byte whose continuation bytes
 * never arrive) used to index past the collected byte buffer and crash — these inputs come
 * straight off the web via Uri.path / query accessors.
 */
class PercentEncoderTest {

    @Test
    fun decode_truncated_multibyte_utf8_yields_replacement_not_crash() {
        // 2-byte lead with no continuation: at end of input, and followed by a plain char.
        assertEquals("�", PercentEncoder.decode("%C3").toString())
        assertEquals("�x", PercentEncoder.decode("%C3x").toString())
        // 3-byte lead with only one continuation byte (each isolated byte is invalid).
        assertEquals("��", PercentEncoder.decode("%E2%82").toString())
        // 4-byte lead with two of three continuation bytes.
        assertEquals("���", PercentEncoder.decode("%F0%9F%98").toString())
        // Truncated sequence, a plain char, then a valid complete sequence.
        assertEquals("�xé", PercentEncoder.decode("%C3x%C3%A9").toString())
    }

    @Test
    fun decode_incomplete_escapes_are_left_as_is() {
        assertEquals("%", PercentEncoder.decode("%").toString())
        assertEquals("%4", PercentEncoder.decode("%4").toString())
        assertEquals("%GG", PercentEncoder.decode("%GG").toString())
        assertEquals("a%zz b", PercentEncoder.decode("a%zz%20b").toString())
    }

    @Test
    fun decode_valid_sequences_still_decode() {
        assertEquals("é", PercentEncoder.decode("%C3%A9").toString())
        assertEquals("€", PercentEncoder.decode("%E2%82%AC").toString())
        assertEquals("😀", PercentEncoder.decode("%F0%9F%98%80").toString())
        assertEquals("hello world", PercentEncoder.decode("hello%20world").toString())
    }

    @Test
    fun uri_accessors_survive_truncated_escapes() {
        // The crash was reachable from any scraped URL — the accessors must not throw.
        val uri = Uri.parse("http://x/%C3?q=%E2%82#f%F0%9F")
        assertEquals("/�", uri.path)
        assertEquals("��", uri.queryParamValue("q"))
        assertEquals("f��", uri.fragment)
    }
}
