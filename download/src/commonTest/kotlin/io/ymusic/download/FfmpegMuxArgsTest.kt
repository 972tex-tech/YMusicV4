package io.ymusic.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The subtitle-muxing argv: captions become extra inputs, mapped subtitle streams with a language tag. */
class FfmpegMuxArgsTest {

    private val cc = listOf(
        SubtitleInput("/tmp/a.en.vtt", "en", "English"),
        SubtitleInput("/tmp/a.pt.vtt", "pt-BR", "Portuguese"),
    )

    @Test
    fun mp4_maps_each_caption_as_a_mov_text_subtitle_stream() {
        val args = mp4MuxArgs("/v", "/a", "/out", cc, listOf("title" to "Song"))
        // Two subtitle inputs after video(0) + audio(1).
        assertEquals(2, args.count { it == "file:/tmp/a.en.vtt" || it == "file:/tmp/a.pt.vtt" })
        assertTrue(args.containsSeq("-map", "2:0"))
        assertTrue(args.containsSeq("-map", "3:0"))
        assertTrue(args.containsSeq("-c:s", "mov_text"))
        // Language tags mapped to ISO-639-2; regional variant folds to base.
        assertTrue(args.containsSeq("-metadata:s:s:0", "language=eng"))
        assertTrue(args.containsSeq("-metadata:s:s:1", "language=por"))
        assertTrue(args.containsSeq("-metadata:s:s:0", "title=English"))
        assertTrue(args.containsSeq("-f", "mp4"))
        assertEquals("file:/out", args.last())
    }

    @Test
    fun m4a_maps_captions_as_mov_text_after_the_audio() {
        val args = m4aArgs("/a", "/out", cc, listOf("title" to "Song"))
        assertTrue(args.containsSeq("-map", "0:a:0"))
        assertTrue(args.containsSeq("-map", "1:0")) // subtitle inputs start at index 1 (no video input)
        assertTrue(args.containsSeq("-map", "2:0"))
        assertTrue(args.containsSeq("-c:a", "copy"))
        assertTrue(args.containsSeq("-c:s", "mov_text"))
        assertTrue(args.containsSeq("-metadata:s:s:1", "language=por"))
        assertTrue(args.containsSeq("-f", "ipod"))
    }

    @Test
    fun m4a_without_captions_has_no_subtitle_flags() {
        val args = m4aArgs("/a", "/out", emptyList(), emptyList())
        assertTrue(args.none { it == "-c:s" || it.startsWith("-metadata:s:s:") })
        assertTrue(args.containsSeq("-c:a", "copy"))
        assertTrue(args.containsSeq("-map", "0:a:0"))
    }

    @Test
    fun webm_uses_webvtt_codec_for_subtitles() {
        val args = webmMuxArgs("/v", "/a", "/out", captions = cc, metadata = emptyList())
        assertTrue(args.containsSeq("-c:s", "webvtt"))
        assertTrue(args.containsSeq("-c:v", "copy"))
        assertTrue(args.containsSeq("-c:a", "copy"))
        assertTrue(args.containsSeq("-metadata:s:s:1", "language=por"))
    }

    @Test
    fun no_captions_produces_no_subtitle_flags() {
        val args = mp4MuxArgs("/v", "/a", "/out", emptyList(), emptyList())
        assertTrue(args.none { it == "-c:s" || it.startsWith("-metadata:s:s:") })
        assertTrue(args.containsSeq("-map", "0:v:0")) // still maps video + audio
        assertTrue(args.containsSeq("-map", "1:a:0"))
        assertTrue(args.containsSeq("-c:a", "copy"))
    }

    @Test
    fun unknown_language_passes_through_base_code() {
        assertEquals("eng", iso639_2("en"))
        assertEquals("por", iso639_2("pt-BR"))
        assertEquals("xx", iso639_2("xx")) // off-catalogue → base code unchanged
    }

    private fun List<String>.containsSeq(a: String, b: String): Boolean =
        indices.any { it + 1 < size && this[it] == a && this[it + 1] == b }
}
