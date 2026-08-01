package ymusic.ytmedia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import ymusic.ytmedia.cache.DiskMediaBlobCache
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The access journal must carry LRU recency across a "restart" (a fresh [DiskMediaBlobCache] over the
 * same dir): the span accessed *least* recently before the restart is the first evicted after it, even
 * though it isn't the oldest file on disk.
 */
class AccessJournalTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ymusic-journal-test").absolutePath
    private val key = "2_251_100"
    private fun bytes(n: Int) = ByteArray(n) { (it and 0xFF).toByte() }

    private fun writeSpan(cache: DiskMediaBlobCache, start: Long, len: Int) {
        val w = cache.writer(key, start, len.toLong())
        w.sink.write(bytes(len)); w.commit()
    }

    private fun readSpan(cache: DiskMediaBlobCache, start: Long, len: Int) {
        cache.read(key, start, len.toLong())?.buffer()?.use { it.readByteArray() } // close → unpin
    }

    @Test
    fun lru_recency_survives_a_restart() = runBlocking {
        FileSystem.SYSTEM.deleteRecursively(dir.toPath())

        // budget 35: three 10-byte spans fit; a fourth commit evicts exactly one.
        withContext(Dispatchers.IO) {
            val first = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, budgetBytes = 35)
            writeSpan(first, 0, 10) // s0
            writeSpan(first, 10, 10) // s1  <-- written last, so newest ON DISK / by mtime
            writeSpan(first, 20, 10) // s2
            // Now re-touch so the LRU order becomes: s1 (oldest), s0, s2 (newest). s1 is NOT touched again.
            readSpan(first, 20, 10) // s2
            readSpan(first, 0, 10) // s0
        }

        // Restart: a brand-new instance over the same dir. It has no in-memory recency — only the journal.
        withContext(Dispatchers.IO) {
            val reopened = DiskMediaBlobCache(dir.toPath(), FileSystem.SYSTEM, budgetBytes = 35)
            writeSpan(reopened, 30, 10) // s3 → total 40 > 35 → evict the LRU span

            // s1 was least-recently-used per the journal → it's the victim, even though s0 is the
            // oldest file on disk. s0, s2, s3 survive.
            assertFalse(reopened.isCached(key, 10, 10), "s1 (journal-LRU) should be evicted")
            assertTrue(reopened.isCached(key, 0, 10), "s0 kept")
            assertTrue(reopened.isCached(key, 20, 10), "s2 kept")
            assertTrue(reopened.isCached(key, 30, 10), "s3 kept")
            assertEquals(30L, reopened.totalSize())
        }
    }
}
