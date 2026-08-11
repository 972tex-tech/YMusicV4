package io.ymusic.dynamicengine

import kmpcommon.io.AsyncSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Direct [DiskCacheStore] tests focused on the **no-zombie** invariant: a blob file on disk must never
 * outlive (nor be created without) its metadata row. Runs on a real `FileSystem.SYSTEM` temp dir with
 * an in-memory SQLite index, so a mismatch between disk and DB is observable.
 */
class DiskCacheStoreTest {

    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-diskcache-test"

    private fun store(): DiskCacheStore {
        FileSystem.SYSTEM.deleteRecursively(dir)
        var tick = 1L
        return DiskCacheStore(testCacheDriver(), dir, FileSystem.SYSTEM, now = { tick++ })
    }

    private fun sourceOf(text: String): AsyncSource = object : AsyncSource {
        private val buf = Buffer().writeUtf8(text)
        override suspend fun read(sink: Buffer, byteCount: Long): Long =
            if (buf.exhausted()) -1L else buf.read(sink, byteCount)
        override fun close() = Unit
    }

    /** Streams a few bytes then throws — a cancelled / failed put mid-stream. */
    private fun failingSource(): AsyncSource = object : AsyncSource {
        private var first = true
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (first) {
                first = false
                sink.writeUtf8("partial")
                return 7L
            }
            throw RuntimeException("stream died")
        }
        override fun close() = Unit
    }

    /** Every cache file on disk — blobs and any leftover temp — i.e. anything that could be a zombie. */
    private fun cacheFilesOnDisk(): Int =
        if (FileSystem.SYSTEM.exists(dir)) {
            FileSystem.SYSTEM.list(dir).count { it.name.endsWith(".v") || it.name.endsWith(".v.tmp") }
        } else {
            0
        }

    @Test
    fun put_creates_a_blob_and_row_and_get_roundtrips() = runTest {
        val s = store()
        s.put("sc", "k", emptyMap(), sourceOf("hello"))
        assertEquals("hello", drain(s, "sc", "k"))
        assertEquals(1, s.list("sc").size)
        assertEquals(1, cacheFilesOnDisk())
    }

    @Test
    fun delete_removes_the_blob_and_the_row() = runTest {
        val s = store()
        s.put("sc", "k", emptyMap(), sourceOf("v"))
        s.delete("sc", "k")
        assertEquals(0, s.list("sc").size)
        assertEquals(0, cacheFilesOnDisk()) // no zombie blob
        assertNull(s.get("sc", "k"))
    }

    @Test
    fun a_failed_put_leaves_no_file_and_no_row() = runTest {
        val s = store()
        assertFailsWith<RuntimeException> { s.put("sc", "k", emptyMap(), failingSource()) }
        assertEquals(0, s.list("sc").size) // no row
        assertEquals(0, cacheFilesOnDisk()) // no zombie blob or tmp
    }

    @Test
    fun a_row_whose_blob_vanished_self_heals_to_a_miss() = runTest {
        val s = store()
        s.put("sc", "k", emptyMap(), sourceOf("v"))
        // Simulate a lost blob (crash / torn delete): remove the only blob file.
        val blob = FileSystem.SYSTEM.list(dir).single { it.name.endsWith(".v") }
        FileSystem.SYSTEM.delete(blob)
        assertNull(s.get("sc", "k")) // dangling row → miss
        assertEquals(0, s.list("sc").size) // and the row was self-healed away
    }

    @Test
    fun blob_files_never_outnumber_rows() = runTest {
        val s = store()
        s.put("a", "1", emptyMap(), sourceOf("x"))
        s.put("a", "2", emptyMap(), sourceOf("yy"))
        s.put("b", "3", emptyMap(), sourceOf("zzz"))
        s.delete("a", "1")
        assertNotNull(s.get("a", "2")) // a read (touch) creates no file
        val rows = s.list("a").size + s.list("b").size
        assertEquals(rows, cacheFilesOnDisk()) // 2 rows, 2 blobs, no zombies
    }

    private suspend fun drain(s: DiskCacheStore, scope: String, key: String): String? {
        val entry = s.get(scope, key) ?: return null
        val out = Buffer()
        entry.open().use { src -> while (src.read(out, 4096L) != -1L) { /* to EOF */ } }
        return out.readUtf8()
    }
}
