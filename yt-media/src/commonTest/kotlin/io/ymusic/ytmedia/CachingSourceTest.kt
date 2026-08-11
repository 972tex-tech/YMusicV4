package io.ymusic.ytmedia

import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.proxy.CachingSource
import io.ymusic.ytmedia.sabr.formatCacheKey
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The progressive fill tee ([CachingSource]) must pass the whole network body through to the reader but tee
 * **only the uncached suffix** into the cache — the caller feeds it `cachedLength` as the skip count so an
 * already-cached leading prefix is never re-written (the scenario: server delivers `0-999`, `0-499` already
 * cached → write only `500-999`).
 */
class CachingSourceTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-cachingsource-test"

    private fun asyncOf(bytes: ByteArray) = object : AsyncSource {
        private val src = Buffer().write(bytes)
        override suspend fun read(sink: Buffer, byteCount: Long): Long =
            if (src.exhausted()) -1 else src.read(sink, byteCount)
        override fun close() {}
    }

    @Test
    fun it_skips_the_cached_prefix_and_writes_only_the_uncached_suffix() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val blobs = DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(testSegmentDriver(), now = { 1_000 }))
        val key = formatCacheKey(251, 100)

        // Pre-cache [0, 500) — the prefix bytes are 0xAA.
        val prefix = ByteArray(500) { 0xAA.toByte() }
        blobs.writer(key, 0, 500).also { it.sink.write(prefix); it.commit() }

        // The server delivers the whole [0, 1000); the suffix [500, 1000) is a distinct fill (0xBB).
        val whole = ByteArray(1000) { if (it < 500) 0xAA.toByte() else 0xBB.toByte() }

        val skip = blobs.cachedLength(key, 0, 1000)
        assertEquals(500, skip, "cachedLength reports the 500-byte cached prefix")
        val writer = blobs.writer(key, skip, 1000 - skip) // writer opens at the first uncached byte
        val tee = CachingSource(asyncOf(whole), writer, skip)

        // Drain the tee the way the platform adapter pumps the body to the WebView.
        val out = Buffer()
        while (tee.read(out, 64) != -1L) { /* pull */ }
        assertContentEquals(whole, out.readByteArray(), "the reader still sees the full range unchanged")

        // The suffix is now cached and reads back as the fill bytes (proving 0xBB, not a re-write of 0xAA).
        assertTrue(blobs.isCached(key, 0, 1000), "the whole range is cached after the fill")
        val suffix = blobs.read(key, 500, 500)!!.buffer().use { it.readByteArray() }
        assertContentEquals(ByteArray(500) { 0xBB.toByte() }, suffix, "only [500,1000) was written, as the suffix")
    }

    @Test
    fun with_no_cached_prefix_it_tees_the_whole_body() = runTest {
        FileSystem.SYSTEM.deleteRecursively(dir)
        val blobs = DiskMediaBlobCache(dir, FileSystem.SYSTEM, SqlDelightBlobIndex(testSegmentDriver(), now = { 1_000 }))
        val key = formatCacheKey(140, 7)

        val whole = ByteArray(300) { (it and 0xFF).toByte() }
        val writer = blobs.writer(key, 0, 300)
        val tee = CachingSource(asyncOf(whole), writer, skip = 0)

        val out = Buffer()
        while (tee.read(out, 128) != -1L) { /* pull */ }
        assertContentEquals(whole, out.readByteArray())

        val cached = blobs.read(key, 0, 300)!!.buffer().use { it.readByteArray() }
        assertContentEquals(whole, cached, "the whole body landed in the cache")
    }
}
