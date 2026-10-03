package io.ymusic.ytmedia

import io.ymusic.ytmedia.proto.ProtoWriter
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.NextRequestPolicy
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrTrack
import io.ymusic.ytmedia.ump.UmpPart
import io.ymusic.ytmedia.ump.writeUmpPart
import io.ymusic.ytmedia.ump.writeUmpPartHeader
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [SabrSession]'s state machine ([SabrSession.nextRequest] / [SabrSession.readResponse])
 * directly with canned UMP responses — no HTTP client needed (the live client path is covered by the
 * `:desktopApp:sabrDownload` end-to-end harness).
 */
class SabrSessionTest {

    private class MemoryFile : DownloadFile {
        val data = Buffer()
        override fun length() = data.size
        override fun appendSink(): BufferedSink = data
        override fun close() {}
    }

    private fun asyncOf(bytes: Buffer): BufferedAsyncSource = BufferedAsyncSource(
        object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long =
                if (bytes.exhausted()) -1 else bytes.read(sink, byteCount)
            override fun close() {}
        },
    )

    private val audioFormat = AdaptiveFormat(
        itag = 251, lmt = 100, xtag = null, mimeType = "audio/webm; codecs=\"opus\"",
        clen = 0, indexRangeStart = 0, indexRangeEnd = 0, width = null, height = null,
        approxDurationMs = 20_000, audioTrackIsDefault = true, audioTrackId = null, isDrc = false,
    )

    private fun Buffer.putSegment(headerId: Int, itag: Int, seg: Int, startRange: Long, data: ByteArray, isInit: Boolean) {
        val header = MediaHeader(
            headerId = headerId, videoId = "vid", itag = itag, lmt = 100, xtag = null,
            startRange = startRange, isInitSegment = isInit, segmentIndex = seg,
            startMs = if (isInit) null else seg * 1000L, durationMs = if (isInit) null else 1000L,
            contentLength = data.size.toLong(),
        ).encode()
        writeUmpPart(UmpPart.MEDIA_HEADER, header)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, data.size + 1L).writeByte(headerId).write(data)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
    }

    private fun Buffer.putPolicy(backoffMs: Int?) {
        writeUmpPart(
            UmpPart.NEXT_REQUEST_POLICY,
            NextRequestPolicy(60_000, 60_000, backoffMs, byteArrayOf(1, 2, 3), null).encode(),
        )
    }

    // Regression: playerTime must advance across responses. Before the fix the round's contiguity
    // check seeded from a fresh per-round cursor, so a new response's first segment (index 3) was
    // checked against -1 not the session's last (2) — never counted, playerTime stuck, download stalls.
    @Test
    fun advances_segment_cursor_across_responses() = runTest {
        val session = SabrSession(random = Random(1))
        val fmt = audioFormat.copy(clen = 1_000_000)
        val audio = SabrTrack(fmt, MemoryFile())

        val req1 = session.nextRequest(spec(fmt), audio, null, null, 0)
        assertEquals(0, req1.clientAbrState.playerTimeMs)

        // Response A: init(0) + seg1 + seg2.
        val a = Buffer().apply {
            putPolicy(null)
            putSegment(0, 251, 0, 0, ByteArray(10), isInit = true)
            putSegment(1, 251, 1, 10, ByteArray(10) { 1 }, isInit = false)
            putSegment(2, 251, 2, 20, ByteArray(10) { 2 }, isInit = false)
        }
        val outA = session.readResponse(asyncOf(a), audio, null)
        assertEquals(3, outA.completedSegments)

        val req2 = session.nextRequest(spec(fmt), audio, null, null, 100)
        assertTrue(req2.clientAbrState.playerTimeMs > 0, "playerTime should advance after buffering")

        // Response B: seg3 + seg4 — must be recognized as continuing seg2.
        val b = Buffer().apply {
            putPolicy(null)
            putSegment(0, 251, 3, 30, ByteArray(10) { 3 }, isInit = false)
            putSegment(1, 251, 4, 40, ByteArray(10) { 4 }, isInit = false)
        }
        val outB = session.readResponse(asyncOf(b), audio, null)
        assertEquals(2, outB.completedSegments, "seg3/4 must continue seg2 across the response boundary")

        val req3 = session.nextRequest(spec(fmt), audio, null, null, 200)
        assertTrue(req3.clientAbrState.playerTimeMs > req2.clientAbrState.playerTimeMs)
        assertEquals(50, audio.file.length()) // init + 4 segments × 10 bytes
    }

    // Full loop simulated against a playerTime-driven server: returns the segment after what the
    // request reports buffered. Stalls (never completes) unless the session advances each round.
    @Test
    fun drives_a_full_download_against_a_playertime_driven_server() = runTest {
        val segCount = 12
        val segBytes = 8
        val total = (segCount + 1).toLong() * segBytes
        val fmt = audioFormat.copy(clen = total)
        val audio = SabrTrack(fmt, MemoryFile())
        val session = SabrSession(random = Random(2))

        var guard = 0
        while (audio.file.length() < total) {
            check(guard++ < 100) { "stalled — cursor not advancing" }
            val req = session.nextRequest(spec(fmt), audio, null, null, guard)
            val bufferedEnd = req.bufferedRanges.maxOfOrNull { it.endSegmentIndex } ?: 0
            val resp = Buffer().apply {
                putPolicy(null)
                if (bufferedEnd == 0) {
                    putSegment(0, 251, 0, 0, ByteArray(segBytes), isInit = true)
                    putSegment(1, 251, 1, segBytes.toLong(), ByteArray(segBytes) { 1 }, isInit = false)
                } else if (bufferedEnd < segCount) {
                    val next = bufferedEnd + 1
                    putSegment(0, 251, next, next.toLong() * segBytes, ByteArray(segBytes) { next.toByte() }, isInit = false)
                }
            }
            session.readResponse(asyncOf(resp), audio, null)
        }
        assertEquals(total, audio.file.length())
    }

    // A malformed control part whose parse error is swallowed (SABR_CONTEXT_UPDATE) must still
    // advance the buffer by its full part size — otherwise leftover bytes desync every part after it.
    @Test
    fun a_malformed_swallowed_part_does_not_desync_following_parts() = runTest {
        val session = SabrSession(random = Random(4))
        val fmt = audioFormat.copy(clen = 20)
        val audio = SabrTrack(fmt, MemoryFile())
        val resp = Buffer().apply {
            putPolicy(null)
            // SABR_CONTEXT_UPDATE part of 5 bytes whose contents claim a 5-byte sub-field after only
            // 2 header bytes → ProtoReader throws mid-part, leaving 3 unconsumed bytes.
            writeUmpPartHeader(UmpPart.SABR_CONTEXT_UPDATE, 5)
                .writeByte(0x0A).writeByte(0x05).writeByte(1).writeByte(2).writeByte(3)
            putSegment(0, 251, 0, 0, ByteArray(10), isInit = true)
            putSegment(1, 251, 1, 10, ByteArray(10) { 1 }, isInit = false)
        }
        val outcome = session.readResponse(asyncOf(resp), audio, null)
        assertEquals(2, outcome.completedSegments, "segments after the bad part must still parse")
        assertEquals(20, audio.file.length())
    }

    @Test
    fun surfaces_ump_redirect_from_a_response() = runTest {
        val session = SabrSession(random = Random(3))
        val audio = SabrTrack(audioFormat.copy(clen = 100), MemoryFile())
        val redirect = Buffer().apply {
            writeUmpPart(
                UmpPart.SABR_REDIRECT,
                ProtoWriter().string(1, "https://r2.googlevideo.com/videoplayback?sabr=1"),
            )
        }
        val outcome = session.readResponse(asyncOf(redirect), audio, null)
        assertEquals("https://r2.googlevideo.com/videoplayback?sabr=1", outcome.redirect)
    }

    private fun spec(fmt: AdaptiveFormat) = io.ymusic.ytmedia.sabr.SabrStreamSpec(
        videoId = "vid",
        sabrUrl = "https://x.googlevideo.com/videoplayback?sabr=1",
        ustreamerConfig = ByteArray(0),
        poToken = byteArrayOf(9),
        clientInfo = io.ymusic.ytmedia.sabr.ClientInfo(clientName = 1),
        formats = listOf(fmt),
    )
}
