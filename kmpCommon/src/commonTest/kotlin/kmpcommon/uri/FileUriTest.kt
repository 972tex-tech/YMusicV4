package kmpcommon.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileUriTest {

    @Test
    fun round_trips_special_characters() {
        val path = "/Music/My Song #1 (100%) — café.mp3"
        val uri = fileUri(path)
        // The built uri is well-formed: the '#' is encoded, not a fragment.
        assertNull(uri.fragment, "a '#' in the name must not become a fragment: $uri")
        assertEquals(path, uri.filePath())
    }

    @Test
    fun filePath_decodes_encoded_uri() {
        assertEquals("/Music/My Song.mp3", Uri.parse("file:///Music/My%20Song.mp3").filePath())
    }

    @Test
    fun canonical_unifies_equivalent_encodings() {
        // Same file, reached two ways — a pre-encoded string vs a raw path — canonicalize equal.
        val fromString = Uri.parse("file:///Music/My%20Song.mp3")
        val fromPath = fileUri("/Music/My Song.mp3")
        assertEquals(fromString.canonicalString(), fromPath.canonicalString())
    }

    @Test
    fun canonical_lowercases_the_scheme() {
        assertEquals(
            Uri.parse("file:///x").canonicalString(),
            Uri.parse("FILE:///x").canonicalString(),
        )
    }

    @Test
    fun canonical_preserves_saf_reserved_encoding() {
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSong.mp3"
        assertEquals(saf, Uri.parse(saf).canonicalString())
    }
}
