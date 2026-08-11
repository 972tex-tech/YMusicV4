package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.sabr.MediaHeader
import io.ymusic.ytmedia.sabr.formatCacheKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SqlDelightSegmentIndex] — the segment ⇄ (time, byte) map queries, ported from legacy SabrCacheDb. */
class SegmentIndexTest {

    private var tick = 1_000L
    private fun index(retention: Long = 30L * 24 * 60 * 60 * 1000) =
        SqlDelightSegmentIndex(testSegmentDriver(), now = { tick }, retentionMillis = retention)

    private val itag = 251
    private val lmt = 1_700_000_000_123_456L

    // Segment k: 1s window starting at k seconds, contiguous 100-byte spans, init at byte 0.
    private fun seg(k: Int) = MediaHeader(
        headerId = 7, videoId = "vid", itag = itag, lmt = lmt, xtag = null,
        startRange = k.toLong() * 100, isInitSegment = k == 0, segmentIndex = k,
        startMs = if (k == 0) null else k * 1000L, durationMs = if (k == 0) null else 1000L,
        contentLength = 100,
    )

    private fun SqlDelightSegmentIndex.saveAll(vararg ks: Int) = ks.forEach { save(seg(it)) }

    @Test
    fun find_by_start_time_returns_the_covering_segment() = runTest {
        val idx = index()
        idx.saveAll(0, 1, 2, 3)
        // playerMs 2500 is inside segment 2's window [2000, 2999].
        val hit = idx.findByStartTime(itag, lmt, 2500)!!
        assertEquals(2, hit.segmentIndex)
        assertEquals(200, hit.startRange)
        assertEquals(100, hit.contentLength)
        assertEquals(2000, hit.startMs)
        assertEquals(1000, hit.durationMs)
    }

    @Test
    fun find_by_start_time_misses_outside_any_window() = runTest {
        val idx = index()
        idx.saveAll(1, 2)
        assertNull(idx.findByStartTime(itag, lmt, 9_999_999))
    }

    @Test
    fun find_by_segment_index_reconstructs_init_with_null_window() = runTest {
        val idx = index()
        idx.saveAll(0, 1)
        val init = idx.findBySegmentIndex(itag, lmt, 0)!!
        assertTrue(init.isInitSegment)
        assertNull(init.startMs)
        assertNull(init.durationMs)
        assertEquals(0, init.startRange)
    }

    @Test
    fun find_by_byte_offset_returns_the_furthest_segment_before_it() = runTest {
        val idx = index()
        idx.saveAll(0, 1, 2, 3)
        // Segments end at bytes 99, 199, 299, 399. Furthest whose r_end <= 250 is segment 1 (ends 199).
        val resume = idx.findByByteOffset(itag, lmt, 250)!!
        assertEquals(1, resume.segmentIndex)
    }

    @Test
    fun save_is_insert_or_ignore() = runTest {
        val idx = index()
        idx.save(seg(1))
        idx.save(seg(1).copy(startRange = 99999)) // same PK → ignored
        assertEquals(100, idx.findBySegmentIndex(itag, lmt, 1)!!.startRange)
    }

    @Test
    fun cleanup_deletes_only_orphan_streams_past_retention() = runTest {
        val idx = index(retention = 100)
        tick = 1_000
        idx.saveAll(0, 1) // stream A (itag/lmt), download_time = 1000
        val otherLmt = 42L
        tick = 1_000
        idx.save(seg(1).copy(lmt = otherLmt)) // stream B, download_time = 1000

        // Advance well past retention so both are age-eligible.
        tick = 5_000
        // Keep stream A (it still has blobs); B is an orphan → deleted.
        idx.cleanup(keepKeys = setOf(formatCacheKey(itag, lmt)))

        assertTrue(idx.findBySegmentIndex(itag, lmt, 1) != null, "kept stream survives")
        assertNull(idx.findBySegmentIndex(itag, otherLmt, 1), "orphan stream past retention deleted")
    }

    @Test
    fun cleanup_spares_a_recent_orphan_within_retention() = runTest {
        val idx = index(retention = 10_000)
        tick = 1_000
        idx.save(seg(1)) // orphan, but young
        tick = 2_000 // only 1000ms old, retention is 10000ms
        idx.cleanup(keepKeys = emptySet())
        assertTrue(idx.findBySegmentIndex(itag, lmt, 1) != null, "young orphan spared until it ages out")
    }

    @Test
    fun mark_download_time_protects_a_stream_from_cleanup() = runTest {
        val idx = index(retention = 100)
        tick = 1_000
        idx.saveAll(1)
        tick = 5_000
        idx.markDownloadTime(itag, lmt) // refresh to now (5000)
        tick = 5_050 // only 50ms since the refresh; retention 100
        idx.cleanup(keepKeys = emptySet())
        assertTrue(idx.findBySegmentIndex(itag, lmt, 1) != null, "recently downloaded stream is protected")
    }
}
