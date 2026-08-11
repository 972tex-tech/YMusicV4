package io.ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediascanner.MediaScanner.Progress
import io.ymusic.mediastore.BetterMediaStore
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
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
        assertTrue(progress.errors.isEmpty())
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
    fun unreadable_folder_is_an_error_not_a_mass_delete() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/a.mp3", 100)
            file("/music/rock/b.flac", 200)
        }
        scanner(fs).scan(uri("file:///music")).last()
        assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))

        // The volume "disconnects": enumerating /music now fails. That must surface as this
        // folder's ScanError and leave the library untouched — reported as an empty folder it
        // would tombstone every item and child bucket under /music in one scan.
        val failing = object : ForwardingFileSystem(fs) {
            override fun list(dir: Path): List<Path> {
                if (dir == "/music".toPath()) throw IOException("Input/output error")
                return super.list(dir)
            }
        }
        val second = MediaScanner.create(
            store, FileSystemDocumentsProvider(failing), FakeMetadataExtractor(emptySet()),
        ).scan(uri("file:///music")).last()

        assertEquals(0, second.deleted, "an unreadable folder must not delete anything")
        assertEquals(1, second.errors.size, "the failure must be surfaced, got ${'$'}{second.errors}")
        assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))
        assertNotNull(store.findItemByUri(uri("file:///music/rock/b.flac")))
        assertNotNull(store.findBucketByUri(uri("file:///music/rock")))
    }

    @Test
    fun probe_timeout_is_surfaced_as_an_error_not_silently_dropped() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/ok.mp3", 100)
            file("/music/slow.mp3", 200)
        }
        val scanner = MediaScanner.create(
            store,
            FileSystemDocumentsProvider(fs),
            FakeMetadataExtractor(timeoutOn = setOf("slow.mp3")),
        )
        val progress = scanner.scan(uri("file:///music")).last()

        assertEquals(1, progress.added, "the good file is indexed")
        assertEquals(1, progress.errors.size, "the timed-out probe is a ScanError, got ${'$'}{progress.errors}")
        assertNotNull(store.findItemByUri(uri("file:///music/ok.mp3")))
        assertNull(store.findItemByUri(uri("file:///music/slow.mp3")))
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
    fun folder_rescan_of_a_rename_replaces_the_row() = runTest {
        val fs = FakeFileSystem().apply { file("/music/song.mp3", 100) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        val originalId = assertNotNull(store.findItemByUri(uri("file:///music/song.mp3"))).id

        fs.delete("/music/song.mp3".toPath())
        fs.file("/music/renamed.mp3", 100)
        s.scan(uri("file:///music")).last()

        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val items = store.itemsInBucket(music.id)
        assertEquals(listOf("renamed.mp3"), items.map { leaf(it.uri) }, "one live item")
        assertNull(store.findItemByUri(uri("file:///music/song.mp3")), "old uri gone")
        // No move-in-place any more: a rename is just a fresh row. Playlist continuity is handled by
        // the content code (see media-store's favorite_follows_content_code_across_a_move).
        assertTrue(items[0].id != originalId, "rename produces a new row")
    }

    @Test
    fun scanning_a_subfolder_directly_keeps_its_parent() = runTest {
        val fs = FakeFileSystem().apply { file("/music/rock/b.flac", 1) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last() // full scan: rock's parent = music

        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val rockBefore = assertNotNull(store.findBucketByUri(uri("file:///music/rock")))
        assertEquals(music.id, rockBefore.parentId)

        // Scan the sub-folder directly — its parent must NOT be reset to null.
        s.scan(uri("file:///music/rock")).last()

        val rockAfter = assertNotNull(store.findBucketByUri(uri("file:///music/rock")))
        assertEquals(music.id, rockAfter.parentId, "parent preserved when scanned as a root")
    }

    // ---- single-file scan ----

    @Test
    fun scan_single_file_indexes_it_as_orphan() = runTest {
        val fs = FakeFileSystem().apply { file("/music/a.mp3", 10) }
        val progress = scanner(fs).scan(uri("file:///music/a.mp3")).last()

        assertEquals(1, progress.added)
        val item = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))
        assertNull(item.bucketId, "orphan until a folder scan adopts it")
    }

    @Test
    fun scan_single_file_into_a_bucket_files_it_there() = runTest {
        // A completed download names the bucket its file lives in, so it's filed (not orphaned).
        val bucket = store.upsertBucket(io.ymusic.mediastore.model.BucketDraft(uri("file:///music"), "music"))
        val fs = FakeFileSystem().apply { file("/music/a.mp3", 10) }
        val progress = scanner(fs).scan(uri("file:///music/a.mp3"), intoBucket = bucket.id).last()

        assertEquals(1, progress.added)
        val item = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))
        assertEquals(bucket.id, item.bucketId, "filed under the named bucket, not an orphan")
        assertEquals(listOf("a.mp3"), store.itemsInBucket(bucket.id).map { leaf(it.uri) })
    }

    @Test
    fun scan_single_non_media_file_does_nothing() = runTest {
        val fs = FakeFileSystem().apply { file("/music/notes.txt", 5) }
        val progress = scanner(fs).scan(uri("file:///music/notes.txt")).last()

        assertEquals(0, progress.added)
        assertNull(store.findItemByUri(uri("file:///music/notes.txt")))
    }

    @Test
    fun folder_scan_adopts_a_previously_orphaned_file() = runTest {
        val fs = FakeFileSystem().apply { file("/music/a.mp3", 10) }
        val s = scanner(fs)
        s.scan(uri("file:///music/a.mp3")).last()
        val orphanId = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3"))).id

        s.scan(uri("file:///music")).last() // now scan the folder
        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val item = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))
        assertEquals(orphanId, item.id, "adopted, not duplicated")
        assertEquals(music.id, item.bucketId, "parent assigned by the folder scan")
        assertTrue(store.itemsInBucket(null).isEmpty(), "no longer an orphan")
    }

    @Test
    fun single_file_scan_of_a_changed_file_keeps_its_parent() = runTest {
        val fs = FakeFileSystem().apply { file("/music/a.mp3", 10) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last() // a.mp3 filed under music
        val music = assertNotNull(store.findBucketByUri(uri("file:///music")))
        val id = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3"))).id

        fs.file("/music/a.mp3", 20) // change it so the single-file scan goes through the update path
        s.scan(uri("file:///music/a.mp3")).last()

        val after = assertNotNull(store.findItemByUri(uri("file:///music/a.mp3")))
        assertEquals(id, after.id, "not duplicated")
        assertEquals(music.id, after.bucketId, "null bucket did not re-orphan the adopted item")
    }

    @Test
    fun rescan_soft_deletes_deep_subtree() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/rock/live/a.mp3", 1)
            file("/music/rock/live/b.mp3", 2)
        }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        val live = assertNotNull(store.findBucketByUri(uri("file:///music/rock/live")))
        assertEquals(2, store.itemsInBucket(live.id).size)

        fs.deleteRecursively("/music/rock".toPath()) // remove the whole subtree
        s.scan(uri("file:///music")).last()

        assertNull(store.findBucketByUri(uri("file:///music/rock")))
        assertNull(store.findBucketByUri(uri("file:///music/rock/live")), "grandchild bucket gone")
        assertNull(store.findItemByUri(uri("file:///music/rock/live/a.mp3")), "grandchild item gone")
    }

    @Test
    fun media_less_folders_are_not_indexed() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/songs/a.mp3", 100) // the only media, deep under empty folders
            createDirectories("/music/empty".toPath())
            createDirectories("/music/code/node_modules/pkg".toPath())
            file("/music/code/readme.txt", 10)
        }
        scanner(fs).scan(uri("file:///music")).last()

        assertNotNull(store.findBucketByUri(uri("file:///music")), "root kept")
        assertNotNull(store.findBucketByUri(uri("file:///music/songs")), "media folder kept")
        assertNull(store.findBucketByUri(uri("file:///music/empty")), "empty folder not indexed")
        assertNull(store.findBucketByUri(uri("file:///music/code")), "media-less tree not indexed")
        assertNull(store.findBucketByUri(uri("file:///music/code/node_modules")), "nested media-less tree not indexed")
        // Only the root + the one media folder — not the whole tree.
        assertEquals(2, store.allBuckets().size)
    }

    @Test
    fun empty_root_is_kept() = runTest {
        val fs = FakeFileSystem().apply { createDirectories("/music/empty".toPath()) }
        scanner(fs).scan(uri("file:///music")).last()

        assertNotNull(store.findBucketByUri(uri("file:///music")), "the scan root is kept even when empty")
        assertNull(store.findBucketByUri(uri("file:///music/empty")))
        assertEquals(1, store.allBuckets().size)
    }

    @Test
    fun folder_pruned_when_it_loses_all_media_but_root_stays() = runTest {
        val fs = FakeFileSystem().apply { file("/music/albums/a.mp3", 100) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        assertNotNull(store.findBucketByUri(uri("file:///music/albums")))

        fs.delete("/music/albums/a.mp3".toPath()) // folder still exists on disk, but now media-less
        s.scan(uri("file:///music")).last()

        assertNotNull(store.findBucketByUri(uri("file:///music")), "root stays")
        assertNull(store.findBucketByUri(uri("file:///music/albums")), "now-media-less folder pruned")
        assertNull(store.findItemByUri(uri("file:///music/albums/a.mp3")))
    }

    @Test
    fun excluded_folder_is_cleaned_up_and_stays_skipped_on_rescan() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/keep/a.mp3", 100)
            file("/music/skip/b.mp3", 200)
            file("/music/skip/deep/c.mp3", 300)
        }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        val skip = assertNotNull(store.findBucketByUri(uri("file:///music/skip")))
        store.setBucketExcluded(skip.id, true)

        // Excluding flags + keeps the folder, deletes its subfolder rows, and soft-deletes its items.
        assertNull(store.findBucketByUri(uri("file:///music/skip/deep")), "subfolder row deleted")
        assertEquals(0, store.itemsInBucket(skip.id).size, "the folder's items are soft-deleted")

        // Rescan: the excluded folder is not walked (so only the kept file is scanned), stays put, and
        // its contents are not revived or re-created while it's excluded.
        val second = s.scan(uri("file:///music")).last()
        assertEquals(0, second.deleted, "excluded folder must not be pruned")
        assertEquals(1, second.scanned, "only the kept file is scanned; the excluded folder is skipped")
        assertNotNull(store.findBucketByUri(uri("file:///music/skip")), "excluded folder kept")
        assertNull(store.findBucketByUri(uri("file:///music/skip/deep")), "subfolder not re-created")
        assertEquals(listOf("a.mp3"), store.allItems().map { leaf(it.uri) }, "excluded songs stay hidden")
    }

    @Test
    fun scanning_an_excluded_folder_directly_does_nothing() = runTest {
        val fs = FakeFileSystem().apply { file("/music/skip/b.mp3", 200) }
        val s = scanner(fs)
        s.scan(uri("file:///music")).last()
        val skip = assertNotNull(store.findBucketByUri(uri("file:///music/skip")))
        store.setBucketExcluded(skip.id, true)

        // Pointing a scan straight at the excluded folder must not revive what exclusion removed.
        val p = s.scan(uri("file:///music/skip")).last()
        assertEquals(0, p.scanned, "nothing scanned")
        assertEquals(0, p.added)
        assertTrue(store.allItems().isEmpty(), "songs stay hidden")
    }

    @Test
    fun scans_many_files_concurrently_without_loss() = runTest {
        // 60 files across 6 folders, scanned by the default 4-worker pool. Before the store
        // serialized DB access this raced the single JDBC connection and dropped writes.
        val fs = FakeFileSystem()
        var size = 0
        for (b in 0 until 6) for (f in 0 until 10) fs.file("/music/b$b/f$f.mp3", ++size)

        val progress = scanner(fs).scan(uri("file:///music")).last()

        assertEquals(60, progress.added)
        assertTrue(progress.errors.isEmpty())
        for (b in 0 until 6) {
            val bucket = assertNotNull(store.findBucketByUri(uri("file:///music/b$b")))
            assertEquals(10, store.itemsInBucket(bucket.id).size, "bucket b$b indexed all 10 files")
        }
    }

    @Test
    fun per_file_error_is_counted_not_fatal() = runTest {
        val fs = FakeFileSystem().apply {
            file("/music/good.mp3", 100)
            file("/music/bad.mp3", 100)
        }
        val progress = scanner(fs, failOn = setOf("bad.mp3")).scan(uri("file:///music")).last()

        assertEquals(1, progress.added)
        assertEquals(1, progress.errors.size)
        assertEquals("file:///music/bad.mp3", progress.errors.single().uri.toString())
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
