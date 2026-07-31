package ymusic.ytmedia

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSink
import ymusic.ytmedia.sabr.AdaptiveFormat
import ymusic.ytmedia.sabr.ClientInfo
import ymusic.ytmedia.sabr.DownloadFile
import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.sabr.NextRequestPolicy
import ymusic.ytmedia.sabr.SabrHttp
import ymusic.ytmedia.sabr.SabrHttpResponse
import ymusic.ytmedia.sabr.SabrSession
import ymusic.ytmedia.sabr.SabrStreamSpec
import ymusic.ytmedia.sabr.SabrTrack
import ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.writeUmpPartHeader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SabrSessionTest {

    private class MemoryFile : DownloadFile {
        val buffer = Buffer()
        override fun length() = buffer.size
        override fun appendSink(): BufferedSink = buffer
        override fun close() {}
    }

    private val audioFormat = AdaptiveFormat(
        itag = 251, lmt = 100, xtag = null, mimeType = "audio/webm; codecs=\"opus\"",
        clen = 0, indexRangeStart = 0, indexRangeEnd = 0, width = null, height = null,
        approxDurationMs = 20_000, audioTrackIsDefault = true, audioTrackId = null, isDrc = false,
    )
    private val videoFormat = audioFormat.copy(itag = 247, mimeType = "video/webm", width = 640, height = 360)

    /** Encode a full segment (header+data+end) into a UMP buffer for one format. */
    private fun Buffer.putSegment(headerId: Int, itag: Int, seg: Int, startRange: Long, data: ByteArray, isInit: Boolean) {
        val header = MediaHeader(
            headerId = headerId, videoId = "vid", itag = itag, lmt = 100, xtag = null,
            startRange = startRange, isInitSegment = isInit, segmentIndex = seg,
            startMs = if (isInit) null else seg * 1000L, durationMs = if (isInit) null else 1000L,
            contentLength = data.size.toLong(),
        ).encode().toByteArray()
        writeUmpPartHeader(UmpPart.MEDIA_HEADER, header.size.toLong()).write(header)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, data.size + 1L).writeByte(headerId).write(data)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
    }

    private fun Buffer.putPolicy(backoffMs: Int?) {
        val payload = NextRequestPolicy(60_000, 60_000, backoffMs, byteArrayOf(1, 2, 3), null).encode().toByteArray()
        writeUmpPartHeader(UmpPart.NEXT_REQUEST_POLICY, payload.size.toLong()).write(payload)
    }

    @Test
    fun downloads_audio_across_multiple_rounds() = runTest {
        // Format is 3 segments (init + 2 media) of 10 bytes each; server returns one media segment per POST.
        val segments = listOf(
            ByteArray(10) { 0 } to true, // init, seg 0
            ByteArray(10) { 1 } to false, // seg 1
            ByteArray(10) { 2 } to false, // seg 2
        )
        val totalLen = segments.sumOf { it.first.size }.toLong()
        val format = audioFormat.copy(clen = totalLen)

        var round = 0
        val http = SabrHttp { _, _, body ->
            // Verify each POST carries a decodable request with our client info.
            val request = VideoPlaybackAbrRequest.from(
                ymusic.ytmedia.proto.ProtoReader(Buffer().write(body)).readAllFields()
            )
            assertEquals(1, request.clientAbrState.enabledTrackTypesBitfield, "audio-only bitfield")
            assertTrue(request.clientAbrState.timeSinceLastSeek!! > 0)

            val out = Buffer()
            out.putPolicy(null)
            when (round) {
                0 -> { // init + seg1 in the first response
                    out.putSegment(0, 251, 0, 0, segments[0].first, isInit = true)
                    out.putSegment(1, 251, 1, 10, segments[1].first, isInit = false)
                }
                else -> out.putSegment(0, 251, 2, 20, segments[2].first, isInit = false)
            }
            round++
            SabrHttpResponse(status = 200, location = null, body = out)
        }

        val file = MemoryFile()
        var lastAudioDownloaded = 0L
        SabrSession(http, random = Random(1)).download(
            spec = SabrStreamSpec("vid", "https://x.googlevideo.com/videoplayback?sabr=1", ByteArray(0), byteArrayOf(9), ClientInfo(clientName = 1), listOf(format)),
            audio = SabrTrack(format, file),
            progress = { a, _, _, _ -> lastAudioDownloaded = a },
        )

        assertEquals(totalLen, file.length())
        assertEquals(totalLen, lastAudioDownloaded)
        // Bytes are the concatenation of the three segments in order.
        val bytes = file.buffer.readByteArray()
        assertTrue(bytes.slice(0..9).all { it == 0.toByte() })
        assertTrue(bytes.slice(10..19).all { it == 1.toByte() })
        assertTrue(bytes.slice(20..29).all { it == 2.toByte() })
    }

    @Test
    fun follows_ump_redirect() = runTest {
        val format = audioFormat.copy(clen = 10)
        val urls = mutableListOf<String>()
        var round = 0
        val http = SabrHttp { url, _, _ ->
            urls += url
            val out = Buffer()
            if (round++ == 0) {
                val redirect = "https://r2.googlevideo.com/videoplayback?sabr=1".encodeToByteArray()
                val payload = ymusic.ytmedia.proto.ProtoWriter().string(1, "https://r2.googlevideo.com/videoplayback?sabr=1").toByteArray()
                out.writeUmpPartHeader(UmpPart.SABR_REDIRECT, payload.size.toLong()).write(payload)
            } else {
                out.putPolicy(null)
                out.putSegment(0, 251, 0, 0, ByteArray(10) { 7 }, isInit = false)
            }
            SabrHttpResponse(200, null, out)
        }
        val file = MemoryFile()
        SabrSession(http, random = Random(2)).download(
            spec = SabrStreamSpec("vid", "https://r1.googlevideo.com/videoplayback?sabr=1", ByteArray(0), null, ClientInfo(), listOf(format)),
            audio = SabrTrack(format, file),
        )
        assertEquals(10, file.length())
        assertTrue(urls[0].startsWith("https://r1."))
        assertTrue(urls.last().startsWith("https://r2."))
    }
}
