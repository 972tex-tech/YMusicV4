package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * canonicalize(alreadyEncoded = true) re-canonicalizes an already-parsed component (the
 * Uri.newBuilder() round trip). A valid %XX that follows a raw disallowed char must be preserved,
 * not swept into the encode run and double-encoded (%20 -> %2520).
 */
class CanonicalizeReencodeTest {

    @Test
    fun already_encoded_preserves_a_valid_escape_after_a_raw_disallowed_char() {
        // A space (disallowed in a path, kept raw by the lenient parser) immediately before a
        // legitimate %20. Re-canonicalizing must encode the space and LEAVE the %20 alone.
        val result = PercentEncoder.canonicalizePath(" %20b", alreadyEncoded = true).toString()
        assertEquals("%20%20b", result)
    }

    @Test
    fun already_encoded_leaves_standalone_escapes_untouched() {
        assertEquals("%20%20", PercentEncoder.canonicalizePath("%20%20", alreadyEncoded = true).toString())
        assertEquals("a%2Fb", PercentEncoder.canonicalizePathSegment("a%2Fb", alreadyEncoded = true).toString())
    }

    @Test
    fun uri_round_trip_through_newBuilder_does_not_double_encode() {
        val uri = Uri.parse("http://h/p%20q")
        val rebuilt = uri.newBuilder().build()
        assertEquals(uri.toString(), rebuilt.toString())
    }
}
