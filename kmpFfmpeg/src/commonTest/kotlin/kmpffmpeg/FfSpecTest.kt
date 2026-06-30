package kmpffmpeg

import kmpcommon.uri.Uri
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toFfmpegSpec] turns a [Uri] into the spec ffmpeg receives. `file:` paths are decoded to a literal
 * path (ffmpeg's file protocol doesn't percent-decode); every other scheme is verbatim.
 */
class FfSpecTest {

    @Test
    fun fileUriDecodesToLiteralPath() {
        assertEquals("file:/music/a b.mp3", Uri.parse("file:/music/a%20b.mp3").toFfmpegSpec())
        assertEquals("file:/music/a b.mp3", Uri.parse("file:/music/a b.mp3").toFfmpegSpec())
    }

    @Test
    fun fileUriWithEmptyAuthorityDecodes() {
        assertEquals("file:/music/a b.mp3", Uri.parse("file:///music/a%20b.mp3").toFfmpegSpec())
    }

    @Test
    fun otherSchemesArePassedVerbatim() {
        // http keeps its proper percent-encoding; pipe/ymix are opaque.
        assertEquals("http://h/a%20b", Uri.parse("http://h/a%20b").toFfmpegSpec())
        assertEquals("pipe:0", Uri.parse("pipe:0").toFfmpegSpec())
        assertEquals("ymix:0sock", Uri.parse("ymix:0sock").toFfmpegSpec())
    }
}
