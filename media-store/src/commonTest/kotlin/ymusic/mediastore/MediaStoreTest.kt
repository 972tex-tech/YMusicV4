package ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ymusic.mediastore.model.BucketDraft
import ymusic.mediastore.model.MediaItemDraft
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStoreTest {

    private lateinit var driver: SqlDriver
    private lateinit var store: BetterMediaStore

    @BeforeTest
    fun setup() {
        driver = testSqlDriver()
        store = BetterMediaStore.create(driver)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    // ---- helpers ----

    private suspend fun bucket(uri: String, name: String = uri, parentId: Long? = null) =
        store.upsertBucket(BucketDraft(uri = Uri.parse(uri), name = name, parentId = parentId))

    private suspend fun item(
        bucketId: Long,
        uri: String,
        fileSize: Long,
        title: String = uri,
        videoId: String? = null,
    ) = store.upsertItem(
        bucketId,
        MediaItemDraft(
            uri = Uri.parse(uri),
            title = title,
            mimeType = "audio/mpeg",
            fileSize = fileSize,
            lastModified = 1_000L,
            youtubeVid = videoId,
        ),
    )

    // ---- buckets ----

    @Test
    fun upsertBucket_inserts_then_updates_by_uri() = runTest {
        val a = bucket("file:///music", name = "music")
        assertTrue(a.id > 0)

        val a2 = bucket("file:///music", name = "renamed")
        assertEquals(a.id, a2.id, "same uri -> same row")
        assertEquals("renamed", a2.name)
        assertEquals(1, store.allBuckets().size)
    }

    @Test
    fun bucket_hierarchy() = runTest {
        val root = bucket("file:///music")
        val child = bucket("file:///music/rock", parentId = root.id)

        assertEquals(listOf(root.id), store.childBuckets(null).map { it.id })
        assertEquals(listOf(child.id), store.childBuckets(root.id).map { it.id })
        assertEquals(root.id, store.findBucket(child.id)!!.parentId)
    }

    @Test
    fun setBucketAlias() = runTest {
        val b = bucket("file:///music")
        store.setBucketAlias(b.id, "My Music")
        assertEquals("My Music", store.findBucket(b.id)!!.aliasName)
        store.setBucketAlias(b.id, null)
        assertNull(store.findBucket(b.id)!!.aliasName)
    }

    @Test
    fun softDeleteBucket_hides_it_and_tombstones_its_items() = runTest {
        val b = bucket("file:///music")
        val it1 = item(b.id, "file:///music/a.mp3", fileSize = 10)

        store.deleteBucket(b.id)
        assertNull(store.findBucket(b.id))
        assertTrue(store.allBuckets().isEmpty())
        assertNull(store.findItem(it1.id), "item soft-deleted with its bucket")
    }

    @Test
    fun upsertBucket_revives_soft_deleted() = runTest {
        val b = bucket("file:///music", name = "music")
        store.deleteBucket(b.id)
        val revived = bucket("file:///music", name = "music-again")
        assertEquals(b.id, revived.id)
        assertNotNull(store.findBucket(b.id))
    }

    @Test
    fun hardDeleteBucket_cascades() = runTest {
        val b = bucket("file:///music")
        val i = item(b.id, "file:///music/a.mp3", fileSize = 10)
        store.deleteBucket(b.id, hard = true)
        // A brand-new bucket at the same uri gets a fresh id (old row truly gone).
        val b2 = bucket("file:///music")
        assertTrue(b2.id > b.id)
        assertNull(store.findItem(i.id))
    }

    @Test
    fun softDeleteBucket_tombstones_the_whole_subtree() = runTest {
        // music > rock > live, an item in each of rock and live.
        val music = bucket("file:///music")
        val rock = bucket("file:///music/rock", parentId = music.id)
        val live = bucket("file:///music/rock/live", parentId = rock.id)
        val rockItem = item(rock.id, "file:///music/rock/a.mp3", fileSize = 1)
        val liveItem = item(live.id, "file:///music/rock/live/b.mp3", fileSize = 2)

        store.deleteBucket(rock.id) // soft

        assertNull(store.findBucket(rock.id))
        assertNull(store.findBucket(live.id), "grandchild bucket tombstoned too")
        assertNull(store.findItem(rockItem.id))
        assertNull(store.findItem(liveItem.id), "grandchild item tombstoned too")
        assertNotNull(store.findBucket(music.id), "ancestor untouched")
    }

    // ---- items ----

    @Test
    fun upsertItem_insert_and_update_by_uri() = runTest {
        val b = bucket("file:///music")
        val i = item(b.id, "file:///music/a.mp3", fileSize = 10, title = "A")
        val i2 = store.upsertItem(
            b.id,
            MediaItemDraft(Uri.parse("file:///music/a.mp3"), title = "A (edited)",
                mimeType = "audio/mpeg", fileSize = 12, lastModified = 2_000),
        )
        assertEquals(i.id, i2.id)
        assertEquals("A (edited)", i2.title)
        assertEquals(12, store.findItem(i.id)!!.fileSize)
        assertEquals(1, store.itemsInBucket(b.id).size)
    }

    @Test
    fun upsertItem_persists_genre() = runTest {
        val b = bucket("file:///music")
        val saved = store.upsertItem(
            b.id,
            MediaItemDraft(Uri.parse("file:///music/g.mp3"), title = "g",
                mimeType = "audio/mpeg", fileSize = 5, lastModified = 1_000, genre = "Synthwave"),
        )
        assertEquals("Synthwave", saved.genre)
        assertEquals("Synthwave", store.findItem(saved.id)!!.genre)
    }

    @Test
    fun upsertItem_inserts_new_when_no_fingerprint_match() = runTest {
        val b = bucket("file:///music")
        val a = item(b.id, "file:///music/a.mp3", fileSize = 1)
        val c = item(b.id, "file:///music/c.mp3", fileSize = 2)
        assertTrue(a.id != c.id)
        assertEquals(2, store.itemsInBucket(b.id).size)
    }

    @Test
    fun upsertItem_null_bucket_orphans_new_and_preserves_existing() = runTest {
        // New item with null bucket → orphan (no bucket).
        val orphan = store.upsertItem(
            null,
            MediaItemDraft(Uri.parse("file:///x.mp3"), title = "x", mimeType = "audio/mpeg",
                fileSize = 1, lastModified = 1),
        )
        assertNull(orphan.bucketId)
        assertEquals(listOf(orphan.id), store.itemsInBucket(null).map { it.id })

        // A filed item re-upserted with null bucket keeps its bucket (COALESCE), not reset to root.
        val b = bucket("file:///music")
        val filed = item(b.id, "file:///music/a.mp3", fileSize = 2)
        val again = store.upsertItem(
            null,
            MediaItemDraft(Uri.parse("file:///music/a.mp3"), title = "a2", mimeType = "audio/mpeg",
                fileSize = 3, lastModified = 3),
        )
        assertEquals(filed.id, again.id)
        assertEquals(b.id, again.bucketId, "null bucket did not reset the existing parent")
    }

    @Test
    fun findItemsByVideoId() = runTest {
        val b = bucket("file:///music")
        item(b.id, "file:///music/[dQw4w9WgXcQ].mp3", fileSize = 1, videoId = "dQw4w9WgXcQ")
        val found = store.findItemsByVideoId("dQw4w9WgXcQ")
        assertEquals(1, found.size)
    }

    @Test
    fun purgeDeletedItems_removes_old_tombstones() = runTest {
        val b = bucket("file:///music")
        val i = item(b.id, "file:///music/a.mp3", fileSize = 1)
        store.deleteItem(i.id)
        val purged = store.purgeDeletedItems(olderThan = Long.MAX_VALUE)
        assertEquals(1, purged)
        // Now the fileSize can't revive it: a re-add is a fresh row.
        val again = item(b.id, "file:///music/a.mp3", fileSize = 1)
        assertTrue(again.id > i.id)
    }

    // ---- playlists ----

    @Test
    fun reserved_playlists_seeded() = runTest {
        val ids = store.playlists().map { it.id }
        assertTrue(BetterMediaStore.FAVORITE_PLAYLIST_ID in ids)
        assertTrue(BetterMediaStore.RECENT_PLAY_PLAYLIST_ID in ids)
    }

    @Test
    fun createRenameDeletePlaylist() = runTest {
        val p = store.createPlaylist("Chill")
        assertTrue(p.id > 0)
        store.renamePlaylist(p.id, "Chill v2")
        assertEquals("Chill v2", store.playlists().first { it.id == p.id }.name)
        store.deletePlaylist(p.id)
        assertTrue(store.playlists().none { it.id == p.id })
    }

    @Test
    fun cannot_delete_reserved_playlist() = runTest {
        assertFailsWith<IllegalArgumentException> {
            store.deletePlaylist(BetterMediaStore.FAVORITE_PLAYLIST_ID)
        }
    }

    @Test
    fun addToPlaylist_appends_and_dedups() = runTest {
        val b = bucket("file:///music")
        val i1 = item(b.id, "file:///music/1.mp3", fileSize = 1)
        val i2 = item(b.id, "file:///music/2.mp3", fileSize = 2)
        val p = store.createPlaylist("P")

        store.addToPlaylist(p.id, listOf(i1, i2, i1)) // duplicate i1 ignored
        assertEquals(listOf(i1.playlistCode, i2.playlistCode), store.playlistItems(p.id).map { it.code })

        store.addToPlaylist(p.id, listOf(i2)) // already present -> no change
        assertEquals(2, store.playlistItems(p.id).size)
    }

    @Test
    fun reorderPlaylist_sets_the_new_order() = runTest {
        val b = bucket("file:///music")
        val items = (1..3).map { item(b.id, "file:///music/$it.mp3", fileSize = it.toLong()) }
        val p = store.createPlaylist("P")
        store.addToPlaylist(p.id, items)

        val codes = items.map { it.playlistCode }
        store.reorderPlaylist(p.id, listOf(codes[2], codes[0], codes[1]))

        assertEquals(listOf(codes[2], codes[0], codes[1]), store.playlistItems(p.id).map { it.code })
    }

    @Test
    fun playlistItemDetails_resolves_code_to_media() = runTest {
        val b = bucket("file:///music")
        val i = item(b.id, "file:///music/a.mp3", fileSize = 1, title = "A")
        val p = store.createPlaylist("P")
        store.addToPlaylist(p.id, listOf(i))

        val details = store.playlistItemDetails(p.id)
        assertEquals(1, details.size)
        assertEquals("A", details[0].mediaItem.title)
        assertEquals(i.playlistCode, details[0].code)
    }

    @Test
    fun favorite_follows_content_code_across_a_move() = runTest {
        val b = bucket("file:///music")
        val a = item(b.id, "file:///music/a.mp3", fileSize = 100)
        store.addToPlaylist(BetterMediaStore.FAVORITE_PLAYLIST_ID, listOf(a))
        assertTrue(store.isFavorite(a))

        // The same file re-appears at a new uri (a fresh row, same fileSize+duration → same code).
        val moved = item(b.id, "file:///music/renamed.mp3", fileSize = 100)
        assertTrue(store.isFavorite(moved), "favorite follows the content code, not the row")

        store.removeFromPlaylist(BetterMediaStore.FAVORITE_PLAYLIST_ID, moved.playlistCode)
        assertTrue(!store.isFavorite(a))
    }

    // ---- reactive ----

    @Test
    fun playlistChanges_emits_current_membership() = runTest {
        val b = bucket("file:///music")
        val i = item(b.id, "file:///music/a.mp3", fileSize = 1)
        val p = store.createPlaylist("P")
        store.addToPlaylist(p.id, listOf(i))

        val emitted = store.playlistChanges(p.id).first()
        assertEquals(listOf(i.playlistCode), emitted.map { it.code })
    }
}
