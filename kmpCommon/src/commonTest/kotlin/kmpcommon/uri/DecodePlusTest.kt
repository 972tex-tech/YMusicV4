package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * plusIsSpace must behave the same regardless of whether the input also contains a percent escape.
 * The old fast path returned early on any '%'-free string, so "a+b" was left unchanged while
 * "a+b%20c" decoded its '+' — the flag's effect depended on unrelated content.
 */
class DecodePlusTest {

    @Test
    fun plus_is_space_applies_without_a_percent_escape() {
        assertEquals("a b", PercentEncoder.decode("a+b", plusIsSpace = true).toString())
        assertEquals("hello world", PercentEncoder.decode("hello+world", plusIsSpace = true).toString())
    }

    @Test
    fun plus_is_space_is_consistent_with_and_without_escapes() {
        assertEquals("a b c", PercentEncoder.decode("a+b%20c", plusIsSpace = true).toString())
        assertEquals("a+b", PercentEncoder.decode("a+b", plusIsSpace = false).toString())
    }

    @Test
    fun plus_free_fast_path_unaffected() {
        assertEquals("a+b", PercentEncoder.decode("a+b").toString())
        assertEquals("plain", PercentEncoder.decode("plain", plusIsSpace = true).toString())
    }
}
