package ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import ymusic.mediascanner.MediaScanner.Progress
import ymusic.mediastore.BetterMediaStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaScannerTest {

    private lateinit var driver: SqlDriver
    private lateinit var store: BetterMediaStore

    @BeforeTest
    fun setup() {
        driver = newTestDriver()
        store = BetterMediaStore.create(driver)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun FakeFileSystem.file(path: String, size: Int) {
        val p = path.toPath()
        p.parent?.let { createDirectories(it) }
        write(p) { write(ByteArray(size)) }
    }

    private fun scanner(fs: FakeFileSystem, failOn: Set<String> = emptySet()) =
        MediaScanner.create(store, FileSystemDocumentsProvider(fs), FakeMetadataExtractor(failOn))

    private fun uri(s: String) = Uri.parse(s)
    private fun leaf(u: Uri) = u.toString().substringAfterLast('/')

    @Test
    fun scans_tree_and_indexes_media() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/a.mp3", 100)
            file("/music/rock/b.flac", 200)
        }
        val progress = scanner(fs).scan(uri("file:///music")).last()

        assertEquals(2, progress.added)
        assertEquals(0, progress.errors)
        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val rock = assertNotNull(store.findBucketByUri(uri("file:///music/rock")))
        assertEquals(music.id, rock.parentId, "rock is a child of music")
        assertEquals(listOf("a.mp3"), store.itemsInBucket(music.id).map { leaf(it.uri) })
        assertEquals(listOf("b.flac"), store.itemsInBucket(rock.id).map { leaf(it.uri) })
    }

    @Test
    fun ignores_non_media_and_dotfolders() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/a.mp3", 100)
            file("/music/notes.txt", 10)
            file("/music/.hidden/secret.mp3", 50)
        }
        scanner(fs).scan(uri("file:///music")).last()

        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        assertEquals(listOf("a.mp3"), store.itemsInBucket(music.id).map { leaf(it.uri) })
        assertNull(store.findBucketByUri(uri("file:///music/.hidden")), "dotfolder not scanned")
    }

    @Test
    fun rescan_unchanged_skips() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/a.mp3", 100)
            file("/music/b.mp3", 200)
        }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()

        val second = s.scan(uri("file:///music")).last()
        assertEquals(0, second.added)
        assertEquals(0, second.updated)
        assertEquals(2, second.scanned)
    }

    @Test
    fun rescan_soft_deletes_vanished() = runTest {
        val fs = FakeFileSystem().apply { file("/music/a.mp3", 100) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))

        fs.delete("/music/a.mp3".toPath())
        val second = s.scan(uri("file:///music")).last()
        assertEquals(1, second.deleted)
        assertNull(store.findItemByUri(uri("file:///music/a.mp3")))
    }

    @Test
    fun detects_move_by_filesize() = runTest {
        val fs = FakeFileSystem().apply { file("/music/song.mp3", 100) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        val originalId = assertNotNull(store.findItemByUri(uri("file:///music/song.mp3"))).id

        // Same content (same size) reappears under a new name → move, not a new row.
        fs.delete("/music/song.mp3".toPath())
        fs.file("/music/renamed.mp3", 100)
        s.scan(uri("file:///music")).last()

        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val items = store.itemsInBucket(music.id)
        assertEquals(1, items.size, "moved file is not duplicated")
        assertEquals(originalId, items[0].id, "row id preserved across the move")
        assertEquals("renamed.mp3", leaf(items[0].uri))
    }

    @Test
    fun per_file_error_is_counted_not_fatal() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/good.mp3", 100)
            file("/music/bad.mp3", 100)
        }
        val progress = scanner(fs, failOn = setOf("bad.mp3")).scan(uri("file:///music")).last()

        assertEquals(1, progress.added)
        assertEquals(1, progress.errors)
        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        assertEquals(listOf("good.mp3"), store.itemsInBucket(music.id).map { leaf(it.uri) })
    }

    @Test
    fun noop_provider_emits_empty_and_completes() = runTest {
        val scanner = MediaScanner.create(store, NoopDocumentsProvider, NoopMetadataExtractor)
        val progress = scanner.scan(uri("file:///anything")).last()

        assertEquals(Progress(), progress)
        assertTrue(store.allBuckets().isEmpty())
    }

    @Test
    fun scan_is_cancelable() = runTest {
        val entered = CompletableDeferred<Unit>()
        val blockForever = CompletableDeferred<List<DocumentInfo>>()
        val provider = object : DocumentsProvider {
            override suspend fun queryDocument(uri: Uri) = DocumentInfo.Folder(uri, "root")
            override suspend fun listChildren(folderUri: Uri): List<DocumentInfo> {
                entered.complete(Unit)
                return blockForever.await() // never completes → the worker parks here
            }
        }
        val scanner = MediaScanner.create(store, provider, FakeMetadataExtractor())

        val job = launch { scanner.scan(uri("file:///x")).collect() }
        entered.await()        // wait until a worker is suspended inside the scan
        job.cancelAndJoin()    // must return promptly
        assertTrue(job.isCancelled)
    }
}
