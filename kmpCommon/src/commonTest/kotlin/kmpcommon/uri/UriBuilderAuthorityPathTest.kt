package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 3986 §3.3: with an authority, the path must be empty or start with '/'. The builder must not
 * emit a URI whose string form re-parses to a different authority than its accessors report.
 */
class UriBuilderAuthorityPathTest {

    @Test
    fun authority_with_slashless_path_gets_a_separator() {
        val uri = UriBuilder().scheme("https").authority("example.com").appendPath("api").build()
        assertEquals("https://example.com/api", uri.toString())
        // And it round-trips: the authority the string parses to matches the one we set.
        assertEquals("example.com", Uri.parse(uri.toString()).authority?.toString())
    }

    @Test
    fun authority_with_path_via_path_setter() {
        val uri = UriBuilder().scheme("https").authority("h.example").path("v1/x").build()
        assertEquals("https://h.example/v1/x", uri.toString())
    }

    @Test
    fun already_slashed_path_is_unchanged() {
        val uri = UriBuilder().scheme("https").authority("h").appendPath("/api").build()
        assertEquals("https://h/api", uri.toString())
    }

    @Test
    fun no_authority_path_is_untouched() {
        val uri = UriBuilder().scheme("mailto").path("a@b.com").build()
        assertEquals("mailto:a@b.com", uri.toString())
    }
}
