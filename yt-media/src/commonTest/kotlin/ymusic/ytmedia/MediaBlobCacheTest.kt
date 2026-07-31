package ymusic.ytmedia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaBlobCache
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The concurrency acceptance bar for [DiskMediaBlobCache] (plan §3a): immutable spans, per-block commit,
 * single-writer dedupe, read pinning across eviction, plan-then-reacquire, and LRU. Runs on a real
 * `FileSystem.SYSTEM` temp dir so a torn write / stray temp is observable on disk.
 */
class MediaBlobCacheTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-blobcache-test"

    private fun cache(budget: Long = 512L * 1024 * 1024, maxSpan: Long = 512L * 1024): DiskMediaBlobCache {
        FileSystem.SYSTEM.deleteRecursively(dir)
        return DiskMediaBlobCache(dir, FileSystem.SYSTEM, budgetBytes = budget, maxSpanBytes = maxSpan)
    }

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) and 0xFF).toByte() }

    private suspend fun write(cache: MediaBlobCache, key: String, pos: Long, data: ByteArray) {
        val w = cache.writer(key, pos, data.size.toLong())
        w.sink.write(data)
        w.commit()
    }

    private suspend fun readAll(cache: MediaBlobCache, key: String, pos: Long, len: Long): ByteArray? {
        val src = cache.read(key, pos, len) ?: return null
        return src.buffer().use { it.readByteArray() }
    }

    private fun spanFiles(key: String): List<String> =
        if (FileSystem.SYSTEM.exists(dir / key)) FileSystem.SYSTEM.list(dir / key).map { it.name } else emptyList()

    @Test
    fun write_then_read_roundtrips_and_reports_cached() = runTest {
        val c = cache()
        val data = bytes(1000)
        write(c, "k", 0, data)
        assertTrue(c.isCached("k", 0, 1000))
        assertContentEquals(data, readAll(c, "k", 0, 1000))
        // A sub-range reads back the right slice.
        assertContentEquals(data.copyOfRange(200, 500), readAll(c, "k", 200, 300))
    }

    @Test
    fun a_gap_is_a_miss_not_a_stall() = runTest {
        val c = cache()
        write(c, "k", 0, bytes(100))
        write(c, "k", 200, bytes(100)) // leaves [100,200) uncached
        assertTrue(c.isCached("k", 0, 100))
        assertNull(readAll(c, "k", 0, 300), "a hole in the range → miss")
        assertNull(readAll(c, "k", 150, 100))
    }

    @Test
    fun contiguous_segments_stitch_across_span_files() = runTest {
        val c = cache()
        // Three segments written independently; contiguous in byte space → one stitched read.
        write(c, "k", 0, bytes(100, seed = 1))
        write(c, "k", 100, bytes(100, seed = 2))
        write(c, "k", 200, bytes(100, seed = 3))
        val expected = bytes(100, 1) + bytes(100, 2) + bytes(100, 3)
        assertContentEquals(expected, readAll(c, "k", 0, 300))
    }

    @Test
    fun a_large_region_commits_as_multiple_blocks_but_reads_as_one() = runTest {
        val c = cache(maxSpan = 256) // force several blocks
        val data = bytes(1000)
        write(c, "k", 0, data)
        assertTrue(spanFiles("k").size >= 4, "1000 bytes / 256 cap ⇒ ≥4 span files")
        assertContentEquals(data, readAll(c, "k", 0, 1000))
    }

    @Test
    fun an_aborted_write_leaves_no_span_and_reads_miss() = runTest {
        val c = cache()
        val w = c.writer("k", 0, 500)
        w.sink.write(bytes(300)) // partial, never all 500
        w.abort()
        assertTrue(spanFiles("k").none { it.endsWith(".tmp") }, "no leftover temp")
        assertTrue(spanFiles("k").none { it.endsWith(".span") }, "nothing committed on abort")
        assertNull(readAll(c, "k", 0, 500))
        assertEquals(0, c.totalSize())
    }

    @Test
    fun an_interrupted_multiblock_write_keeps_its_committed_blocks() = runTest {
        val c = cache(maxSpan = 256)
        val w = c.writer("k", 0, 1000)
        w.sink.write(bytes(600)) // ≥2 full 256-blocks commit mid-stream; the tail is uncommitted
        w.sink.flush()
        w.abort() // drop only the trailing partial block
        // The two full blocks [0,256) and [256,512) survive as valid cached bytes.
        assertTrue(c.isCached("k", 0, 512), "committed blocks survive an abort")
        assertNull(readAll(c, "k", 0, 1000), "the whole region is not cached")
        assertContentEquals(bytes(512), readAll(c, "k", 0, 512))
    }

    @Test
    fun two_coroutines_writing_the_same_region_dedupe_to_one() = runTest {
        val c = cache()
        val data = bytes(2000)
        coroutineScope {
            (0 until 8).map {
                async(Dispatchers.Default) {
                    val w = c.writer("k", 0, 2000)
                    w.sink.write(data)
                    w.commit()
                }
            }.awaitAll()
        }
        assertContentEquals(data, readAll(c, "k", 0, 2000))
        // Dedupe proof: one real writer won the region, so exactly one span file and no double-count.
        assertEquals(1, spanFiles("k").count { it.startsWith("0_") && it.endsWith(".span") })
        assertEquals(2000, c.totalSize())
    }

    @Test
    fun a_pinned_span_survives_eviction_pressure() = runTest {
        val c = cache(budget = 1500) // tiny budget: writing more evicts LRU
        write(c, "k", 0, bytes(500)) // span A, will be the LRU victim
        write(c, "k", 500, bytes(500)) // span B
        // Open (pin) span A's bytes and hold the source open.
        val pinned = c.read("k", 0, 500)!!
        // Now push total over budget so eviction must run — but A is pinned.
        write(c, "k", 1000, bytes(500))
        write(c, "k", 1500, bytes(500))
        // A is still readable through the pinned handle...
        val fromPinned = pinned.buffer().readByteArray()
        assertContentEquals(bytes(500), fromPinned)
        pinned.close()
        // ...and the budget was still enforced by evicting some *unpinned* span instead.
        assertTrue(c.totalSize() <= 1500, "budget honored via unpinned victims")
    }

    @Test
    fun lru_evicts_least_recently_used_first() = runTest {
        val c = cache(budget = 1000, maxSpan = 512)
        write(c, "a", 0, bytes(400))
        write(c, "b", 0, bytes(400))
        // Touch "a" so "b" is now least-recently-used.
        assertNotNull(readAll(c, "a", 0, 400))
        write(c, "c", 0, bytes(400)) // 1200 > 1000 ⇒ evict the LRU, which is "b"
        assertTrue(c.isCached("a", 0, 400), "recently-read 'a' kept")
        assertTrue(c.isCached("c", 0, 400), "just-written 'c' kept")
        assertTrue(!c.isCached("b", 0, 400), "'b' evicted as LRU")
    }

    @Test
    fun reopening_a_dir_recovers_committed_spans_and_sweeps_temps() = runTest {
        val c1 = cache()
        write(c1, "k", 0, bytes(300))
        // A stray temp file, as if a writer crashed mid-block.
        FileSystem.SYSTEM.write(dir / "k" / "999_abc.tmp") { write(bytes(10)) }
        // A fresh cache over the same dir recovers the committed span and sweeps the temp.
        val c2 = DiskMediaBlobCache(dir, FileSystem.SYSTEM)
        assertContentEquals(bytes(300), readAll(c2, "k", 0, 300))
        assertEquals(setOf("k"), c2.keys())
        assertTrue(spanFiles("k").none { it.endsWith(".tmp") }, "temp swept on load")
    }
}
