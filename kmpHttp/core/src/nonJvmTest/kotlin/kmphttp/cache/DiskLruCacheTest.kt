package kmphttp.cache

import kmphttp.runTestSkipBrowser
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for DiskLruCache implementation.
 */
@OptIn(ExperimentalUuidApi::class)
class DiskLruCacheTest {

    private val fileSystem = FileSystem.SYSTEM
    private val testBaseDir = ".".toPath() / "test-cache"

    private fun newCacheDir(): Path {
        val dir = testBaseDir / Uuid.random().toString()
        fileSystem.createDirectories(dir)
        return dir
    }

    private fun cleanupDir(dir: Path) {
        try {
            fileSystem.deleteRecursively(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // region Basic Operations

    @Test
    fun testEmptyCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()
            assertTrue(cache.initialized)
            assertEquals(0L, cache.currentSize)
            assertNull(cache.get("nonexistent"))
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testWriteAndReadEntry() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Write entry
            val editor = cache.edit("test-key")!!
            editor.writeString(0, "value0")
            editor.writeString(1, "value1")
            assertTrue(editor.commit())

            // Read entry
            val snapshot = cache.get("test-key")!!
            assertEquals("value0", snapshot.getString(0))
            assertEquals("value1", snapshot.getString(1))
            snapshot.close()

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testRemoveEntry() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Write entry
            val editor = cache.edit("test-key")!!
            editor.writeString(0, "value0")
            editor.writeString(1, "value1")
            editor.commit()

            assertNotNull(cache.get("test-key")?.also { it.close() })

            // Remove entry
            assertTrue(cache.remove("test-key"))
            assertNull(cache.get("test-key"))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testAbortEdit() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Start edit and abort
            val editor = cache.edit("test-key")!!
            editor.writeString(0, "value0")
            editor.writeString(1, "value1")
            editor.abort()

            // Entry should not exist
            assertNull(cache.get("test-key"))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Key Validation

    @Test
    fun testValidKeys() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Valid keys: lowercase letters, digits, underscore, dash, 1-50 chars
            val validKeys = listOf(
                "a",
                "abc",
                "abc123",
                "a-b-c",
                "a_b_c",
                "0123456789",
                "abcdefghijklmnopqrstuvwxyz_0123456789-test12" // 46 chars
            )

            for (key in validKeys) {
                val editor = cache.edit(key)
                assertNotNull(editor, "Failed for key: $key")
                editor.abort()
            }

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testInvalidKeyWithSpace() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            assertFailsWith<IllegalArgumentException> {
                cache.edit("has space")
            }

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testInvalidKeyWithUppercase() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            assertFailsWith<IllegalArgumentException> {
                cache.edit("HasUppercase")
            }

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testInvalidKeyTooLong() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            val longKey = "a".repeat(121)
            assertFailsWith<IllegalArgumentException> {
                cache.edit(longKey)
            }

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testInvalidKeyEmpty() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            assertFailsWith<IllegalArgumentException> {
                cache.edit("")
            }

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Persistence

    @Test
    fun testPersistenceAcrossRestart() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            // First session: write data
            val cache1 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache1.initialize()

            val editor = cache1.edit("persistent-key")!!
            editor.writeString(0, "persisted-value-0")
            editor.writeString(1, "persisted-value-1")
            assertTrue(editor.commit())
            cache1.close()

            // Second session: read data
            val cache2 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache2.initialize()

            val snapshot = cache2.get("persistent-key")
            assertNotNull(snapshot)
            assertEquals("persisted-value-0", snapshot.getString(0))
            assertEquals("persisted-value-1", snapshot.getString(1))
            snapshot.close()
            cache2.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testMultipleEntriesPersistence() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            // First session: write multiple entries
            val cache1 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 10240)
            cache1.initialize()

            for (i in 1..5) {
                val editor = cache1.edit("key$i")!!
                editor.writeString(0, "value-$i-0")
                editor.writeString(1, "value-$i-1")
                editor.commit()
            }
            cache1.close()

            // Second session: verify all entries
            val cache2 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 10240)
            cache2.initialize()

            for (i in 1..5) {
                val snapshot = cache2.get("key$i")!!
                assertEquals("value-$i-0", snapshot.getString(0))
                assertEquals("value-$i-1", snapshot.getString(1))
                snapshot.close()
            }
            cache2.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region LRU Eviction

    @Test
    fun testEvictionOnInsert() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            // Small cache: 20 bytes
            val cache = DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 20)
            cache.initialize()

            // Write entry a: 4 bytes total (aa + bb)
            val editorA = cache.edit("a")!!
            editorA.writeString(0, "aa")
            editorA.writeString(1, "bb")
            editorA.commit()
            cache.flush()
            assertEquals(4L, cache.currentSize)

            // Write entry b: 6 bytes total (bbb + ccc)
            val editorB = cache.edit("b")!!
            editorB.writeString(0, "bbb")
            editorB.writeString(1, "ccc")
            editorB.commit()
            cache.flush()
            assertEquals(10L, cache.currentSize)

            // Write entry c: 12 bytes total (cccccc + dddddd)
            // Total would be 22, exceeds 20, so 'a' should be evicted
            val editorC = cache.edit("c")!!
            editorC.writeString(0, "cccccc")
            editorC.writeString(1, "dddddd")
            editorC.commit()
            cache.flush()

            cache.trimNow()

            // 'a' should be evicted (LRU)
            assertNull(cache.get("a"))
            assertNotNull(cache.get("b")?.also { it.close() })
            assertNotNull(cache.get("c")?.also { it.close() })

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testLruOrderUpdatedOnRead() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            // Small cache: 18 bytes (enough for 3 entries of 6 bytes each)
            val cache = DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 18)
            cache.initialize()

            // Write entries a, b, c (each 6 bytes)
            for (key in listOf("a", "b", "c")) {
                val editor = cache.edit(key)!!
                editor.writeString(0, "xxx")
                editor.writeString(1, "yyy")
                editor.commit()
            }
            cache.flush()
            assertEquals(18L, cache.currentSize)

            // Read 'a' to make it most recently used
            // LRU order is now: b, c, a (b is oldest)
            cache.get("a")!!.close()

            // Write entry d (6 bytes) - total would be 24, exceeds 18
            // Should evict 'b' (oldest) to make room
            val editorD = cache.edit("d")!!
            editorD.writeString(0, "xxx")
            editorD.writeString(1, "yyy")
            editorD.commit()
            cache.flush()

            // Write entry e (6 bytes) - total would be 24 again, exceeds 18
            // Should evict 'c' (now oldest) to make room
            val editorE = cache.edit("e")!!
            editorE.writeString(0, "xxx")
            editorE.writeString(1, "yyy")
            editorE.commit()
            cache.flush()

            cache.trimNow()

            // 'a' should still exist because we read it (made it recent)
            assertNotNull(cache.get("a")?.also { it.close() })
            // 'b' should be evicted (was oldest when 'd' was added)
            assertNull(cache.get("b"))
            // 'c' should be evicted (was oldest when 'e' was added)
            assertNull(cache.get("c"))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Update Existing Entry

    @Test
    fun testUpdateExistingEntry() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Write initial entry
            val editor1 = cache.edit("test-key")!!
            editor1.writeString(0, "original-0")
            editor1.writeString(1, "original-1")
            editor1.commit()

            // Update entry
            val editor2 = cache.edit("test-key")!!
            editor2.writeString(0, "updated-0")
            editor2.writeString(1, "updated-1")
            editor2.commit()

            // Verify update
            val snapshot = cache.get("test-key")!!
            assertEquals("updated-0", snapshot.getString(0))
            assertEquals("updated-1", snapshot.getString(1))
            snapshot.close()

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testPartialUpdateReusesPreviousValues() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Write initial entry
            val editor1 = cache.edit("test-key")!!
            editor1.writeString(0, "original-0")
            editor1.writeString(1, "original-1")
            editor1.commit()

            // Partial update: only update index 0
            val editor2 = cache.edit("test-key")!!
            editor2.writeString(0, "updated-0")
            // Don't set index 1 - should reuse original
            editor2.commit()

            // Verify: index 0 updated, index 1 unchanged
            val snapshot = cache.get("test-key")!!
            assertEquals("updated-0", snapshot.getString(0))
            assertEquals("original-1", snapshot.getString(1))
            snapshot.close()

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Multiple Editors

    @Test
    fun testMultipleEditorsOnSameKey() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // This implementation allows multiple concurrent editors (unlike OkHttp)
            // The editor with higher ID wins on commit
            val editor1 = cache.edit("test-key")!!
            val editor2 = cache.edit("test-key")!!

            // Set values in editor1
            editor1.writeString(0, "editor1-0")
            editor1.writeString(1, "editor1-1")

            // Set values in editor2
            editor2.writeString(0, "editor2-0")
            editor2.writeString(1, "editor2-1")

            // Commit editor1 first
            assertTrue(editor1.commit())

            // Commit editor2 (higher ID should win)
            assertTrue(editor2.commit())

            // Verify editor2's values are stored
            val snapshot = cache.get("test-key")!!
            assertEquals("editor2-0", snapshot.getString(0))
            assertEquals("editor2-1", snapshot.getString(1))
            snapshot.close()

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region New Entry Requires All Values

    @Test
    fun testNewEntryRequiresAllValues() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Try to create entry with only one value
            val editor = cache.edit("test-key")!!
            editor.writeString(0, "only-value-0")
            // Missing value for index 1

            // Commit should fail
            assertFailsWith<IllegalArgumentException> {
                editor.commit()
            }

            // Entry should not exist
            assertNull(cache.get("test-key"))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Edit from Snapshot

    @Test
    fun testEditFromSnapshot() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Create entry
            val editor1 = cache.edit("test-key")!!
            editor1.writeString(0, "original-0")
            editor1.writeString(1, "original-1")
            editor1.commit()

            // Get snapshot and edit from it
            val snapshot = cache.get("test-key")!!
            val editor2 = snapshot.edit()!!
            snapshot.close()

            editor2.writeString(0, "updated-0")
            editor2.commit()

            // Verify
            val snapshot2 = cache.get("test-key")!!
            assertEquals("updated-0", snapshot2.getString(0))
            assertEquals("original-1", snapshot2.getString(1))
            snapshot2.close()

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testEditFromSnapshotAfterEntryRemoved() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Create entry
            val editor1 = cache.edit("test-key")!!
            editor1.writeString(0, "original-0")
            editor1.writeString(1, "original-1")
            editor1.commit()

            // Get snapshot
            val snapshot = cache.get("test-key")!!
            snapshot.close()

            // Remove entry
            cache.remove("test-key")

            // Edit from snapshot should return null
            assertNull(snapshot.edit())

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testEditDeleteThenCommitShouldFail() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache.initialize()

            // Create entry
            val editor1 = cache.edit("test-key")!!
            editor1.writeString(0, "original-0")
            editor1.writeString(1, "original-1")
            editor1.commit()

            // Start editing
            val editor2 = cache.edit("test-key")!!
            editor2.writeString(0, "updated-0")
            editor2.writeString(1, "updated-1")

            // Delete entry while editing
            assertTrue(cache.remove("test-key"))

            // Commit should fail (entry was deleted)
            assertFalse(editor2.commit())

            // Entry should not exist
            assertNull(cache.get("test-key"))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Size and MaxSize

    @Test
    fun testCurrentSizeCalculation() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 10240)
            cache.initialize()
            assertEquals(0L, cache.currentSize)

            // Write 10 bytes
            val editor = cache.edit("test-key")!!
            editor.writeString(0, "12345") // 5 bytes
            editor.writeString(1, "67890") // 5 bytes
            editor.commit()

            assertEquals(10L, cache.currentSize)

            // Update to 20 bytes
            val editor2 = cache.edit("test-key")!!
            editor2.writeString(0, "1234567890") // 10 bytes
            editor2.writeString(1, "abcdefghij") // 10 bytes
            editor2.commit()

            assertEquals(20L, cache.currentSize)

            // Remove entry
            cache.remove("test-key")
            assertEquals(0L, cache.currentSize)

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testSetMaxSize() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 100)
            cache.initialize()

            // Write entries
            for (i in 1..5) {
                val editor = cache.edit("key$i")!!
                editor.writeString(0, "a".repeat(5))
                editor.writeString(1, "b".repeat(5))
                editor.commit()
            }
            cache.flush()
            assertEquals(50L, cache.currentSize)

            // Reduce max size to trigger eviction
            cache.setMaxSize(20)
            cache.flush()

            assertTrue(cache.currentSize <= 20L)

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Journal File Handling

    @Test
    fun testJournalBackupRecovery() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            // First session: write data
            val cache1 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache1.initialize()

            val editor = cache1.edit("test-key")!!
            editor.writeString(0, "value-0")
            editor.writeString(1, "value-1")
            editor.commit()
            cache1.close()

            // Simulate journal corruption by renaming to backup
            val journalFile = cacheDir / DiskLruCache.JOURNAL_FILE
            val backupFile = cacheDir / DiskLruCache.JOURNAL_FILE_BACKUP
            if (fileSystem.exists(journalFile)) {
                fileSystem.atomicMove(journalFile, backupFile)
            }

            // Second session: should recover from backup
            val cache2 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache2.initialize()

            val snapshot = cache2.get("test-key")!!
            assertEquals("value-0", snapshot.getString(0))
            assertEquals("value-1", snapshot.getString(1))
            snapshot.close()

            // Backup should be deleted
            assertFalse(fileSystem.exists(backupFile))

            cache2.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Close and Reopen

    @Test
    fun testCloseAndReopenCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache1 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache1.initialize()

            val editor = cache1.edit("test-key")!!
            editor.writeString(0, "value-0")
            editor.writeString(1, "value-1")
            editor.commit()

            assertFalse(cache1.closed)
            cache1.close()
            assertTrue(cache1.closed)

            // Reopen
            val cache2 =
                DiskLruCache(fileSystem, directory = cacheDir, valueCount = 2, maxSize = 1024)
            cache2.initialize()

            val snapshot = cache2.get("test-key")!!
            assertEquals("value-0", snapshot.getString(0))
            snapshot.close()

            cache2.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Helpers

    private fun DiskLruCache.Editor.writeString(index: Int, value: String) {
        val sink = newSink(index)
        val buffer = Buffer().apply { writeUtf8(value) }
        sink.write(buffer, buffer.size)
        sink.close()
    }

    private fun DiskLruCache.Snapshot.getString(index: Int): String {
        return getSource(index).buffer().use { it.readUtf8() }
    }

    // endregion
}
