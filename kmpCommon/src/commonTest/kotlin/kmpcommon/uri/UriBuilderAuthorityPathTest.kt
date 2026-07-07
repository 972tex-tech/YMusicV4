package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 3986 §3.3: with an authority, the path must be empty or start with '/'. build() validates
 * through create(), so an invalid combination is rejected rather than silently emitting a URI
 * whose string form re-parses to a different authority than its accessors report.
 */
class UriBuilderAuthorityPathTest {

    @Test
    fun authority_with_slashless_path_is_rejected() {
        assertFailsWith<UriSyntaxException> {
            UriBuilder().scheme("https").authority("example.com").appendPath("api").build()
        }
        assertFailsWith<UriSyntaxException> {
            UriBuilder().scheme("https").authority("h.example").path("v1/x").build()
        }
    }

    @Test
    fun authority_with_absolute_path_builds() {
        val uri = UriBuilder().scheme("https").authority("h").appendPath("/api").build()
        assertEquals("https://h/api", uri.toString())
        assertEquals("h", Uri.parse(uri.toString()).authority?.toString())
    }

    @Test
    fun authority_with_empty_path_builds() {
        val uri = UriBuilder().scheme("https").authority("example.com").build()
        assertEquals("https://example.com", uri.toString())
    }

    @Test
    fun no_authority_relative_path_builds() {
        val uri = UriBuilder().scheme("mailto").path("a@b.com").build()
        assertEquals("mailto:a@b.com", uri.toString())
    }
}
