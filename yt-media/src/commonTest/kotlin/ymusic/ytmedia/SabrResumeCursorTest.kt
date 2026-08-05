package ymusic.ytmedia

import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.sabr.SabrResumeCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SabrResumeCursorTest {

    private fun header(itag: Int, seg: Int, startMs: Long, durMs: Long, startRange: Long) = MediaHeader(
        headerId = 0, videoId = "vid12345678", itag = itag, lmt = 42L, xtag = null,
        startRange = startRange, isInitSegment = false, segmentIndex = seg,
        startMs = startMs, durationMs = durMs, contentLength = 1000L,
    )

    @Test
    fun round_trips_audio_and_video() {
        val audio = header(140, seg = 91, startMs = 900_000, durMs = 10_000, startRange = 14_708_547)
        val video = header(248, seg = 166, startMs = 900_000, durMs = 10_000, startRange = 40_000_000)
        val (a, v) = SabrResumeCursor.decode(SabrResumeCursor.encode(audio, video))
        assertEquals(91, a?.segmentIndex)
        assertEquals(900_000, a?.startMs)
        assertEquals(10_000, a?.durationMs)
        assertEquals(140, a?.itag)
        assertEquals(166, v?.segmentIndex)
        assertEquals(248, v?.itag)
    }

    @Test
    fun round_trips_audio_only() {
        val (a, v) = SabrResumeCursor.decode(SabrResumeCursor.encode(header(140, 5, 50_000, 10_000, 800_000), null))
        assertEquals(5, a?.segmentIndex)
        assertNull(v)
    }

    @Test
    fun garbage_decodes_to_nulls() {
        val (a, v) = SabrResumeCursor.decode(byteArrayOf(1, 2, 3, 4, 5))
        // Unparseable → both null (best-effort; a fresh download from zero).
        assertNull(a)
        assertNull(v)
    }
}
