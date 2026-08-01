package ymusic.ytmedia

import kmpcommon.io.asAsyncSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import ymusic.ytmedia.proxy.SabrCacheTee
import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.sabr.formatCacheKey
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.writeUmpPartHeader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SABR fill-on-forward: [SabrCacheTee] must pass the UMP response through **byte-for-byte** while
 * mirroring each COMPLETE segment into the cache — and drop a segment truncated by a short stream.
 */
class SabrCacheTeeTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-sabrtee-test").absolutePath
    private val itag = 251
    private val lmt = 100L
    private val key = formatCacheKey(itag, lmt)

    private fun header(headerId: Int, startRange: Long, len: Long, seg: Int) = MediaHeader(
        headerId = headerId, videoId = "vid00000001", itag = itag, lmt = lmt, xtag = null,
        startRange = startRange, isInitSegment = false, segmentIndex = seg,
        startMs = (seg - 1) * 5000L, durationMs = 5000L, contentLength = len,
    )

    private fun Buffer.segment(header: MediaHeader, media: ByteArray) {
        val hb = header.encode().toByteArray()
        writeUmpPartHeader(UmpPart.MEDIA_HEADER, hb.size.toLong()).write(hb)
        writeUmpPartHeader(UmpPart.MEDIA_DATA, media.size + 1L).writeByte(header.headerId).write(media)
        writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(header.headerId)
    }

    @Test
    fun tees_complete_segments_and_passes_bytes_through_unchanged() = runBlocking {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())
        val driver = jvmSegmentDatabaseDriver()
        val blobs = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, driver)
        val cache = MediaCache(blobs, SqlDelightSegmentIndex(driver, now = { 0 }))

        val seg0 = ByteArray(10) { it.toByte() }
        val seg1 = ByteArray(8) { (it + 100).toByte() }
        // A third segment whose MEDIA_DATA is cut short (no MEDIA_END) — must NOT be cached.
        val seg2Partial = ByteArray(3) { (it + 200).toByte() }

        val body = Buffer().apply {
            segment(header(0, 0, 10, 1), seg0)
            segment(header(1, 10, 8, 2), seg1)
            // truncated tail: header promises 6 bytes, only 3 data bytes arrive, stream ends (no END)
            val h2 = header(2, 18, 6, 3)
            val hb = h2.encode().toByteArray()
            writeUmpPartHeader(UmpPart.MEDIA_HEADER, hb.size.toLong()).write(hb)
            writeUmpPartHeader(UmpPart.MEDIA_DATA, seg2Partial.size + 1L).writeByte(2).write(seg2Partial)
        }
        val original = body.snapshot().toByteArray()

        // Drain through the tee in tiny reads so every UMP part header/payload straddles a read boundary.
        val passed = Buffer()
        withContext(Dispatchers.IO) {
            SabrCacheTee(body.asAsyncSource(), cache).use { tee ->
                val chunk = Buffer()
                while (true) {
                    val n = tee.read(chunk, 4)
                    if (n == -1L) break
                    passed.writeAll(chunk)
                }
            }
        }

        // 1) pass-through is byte-exact.
        assertContentEquals(original, passed.readByteArray(), "passthrough must be unchanged")

        // 2) both complete segments are cached and read back byte-identical.
        assertTrue(blobs.isCached(key, 0, 10), "seg0 cached")
        assertTrue(blobs.isCached(key, 10, 8), "seg1 cached")
        val readBack = blobs.read(key, 0, 18)?.buffer()?.use { it.readByteArray() }
        assertContentEquals(seg0 + seg1, readBack, "cached bytes == segment media")

        // 3) the truncated segment left no partial span.
        assertFalse(blobs.isCached(key, 18, 6), "truncated seg2 must not be cached")
        assertFalse(blobs.isCached(key, 18, 3), "not even the partial prefix")
        assertEquals(18L, blobs.totalSize(), "only the two complete segments' bytes are stored")
    }
}
