package ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.Flow
import ymusic.mediastore.internal.BetterMediaStoreImpl
import ymusic.mediastore.model.BucketDraft
import ymusic.mediastore.model.MediaItemDraft
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist
import ymusic.mediastore.model.PlaylistItem
import ymusic.mediastore.model.PlaylistItemDetail

/**
 * A media-metadata database (buckets, media items, playlists). CRUD + queries + reactive change
 * flows over a SQLite backend, no scanning or file I/O — that belongs to a separate media-scanner
 * that drives this store.
 *
 * All operations are coroutine-based and safe to call from the main thread (I/O is dispatched
 * internally). Obtain an instance via [create] with a platform SqlDriver (see the per-platform
 * `mediaStore*Driver` builders).
 */
interface BetterMediaStore {

    // ---- buckets ----

    /**
     * Insert the bucket, or update the existing one that has the same [BucketDraft.uri] (reviving it
     * if it was soft-deleted). Returns the stored bucket.
     */
    suspend fun upsertBucket(draft: BucketDraft): MediaStoreBucket

    suspend fun setBucketAlias(bucketId: Long, alias: String?)

    suspend fun findBucket(bucketId: Long): MediaStoreBucket?
    suspend fun findBucketByUri(uri: Uri): MediaStoreBucket?

    /** Child buckets of [parentId], or the root buckets when [parentId] is null. */
    suspend fun childBuckets(parentId: Long?): List<MediaStoreBucket>
    suspend fun allBuckets(): List<MediaStoreBucket>

    /**
     * Remove a bucket. By default a soft delete (tombstone, cascades to its items as tombstones);
     * pass [hard] = true to hard-delete it and cascade-delete its items and their playlist links.
     */
    suspend fun deleteBucket(bucketId: Long, hard: Boolean = false)

    // ---- items ----

    /**
     * Insert the item into [bucketId], or update in place when the same file is already known —
     * resolved in three steps so playlist links (which reference the row id) survive:
     *  1. a row with the same [MediaItemDraft.uri] → update in place;
     *  2. else a soft-deleted row in the same bucket with the same [MediaItemDraft.fileSize] → the
     *     file was moved/renamed: rewrite its uri + metadata, revive it, keep the id;
     *  3. else insert a new row.
     *
     * [bucketId] = null means orphan: a new item is inserted as an orphan (no bucket), and an
     * *existing* item keeps its current bucket (null never re-orphans a filed item). A non-null value
     * adopts the item into that bucket — e.g. a folder scan claiming an orphan a single-file scan created.
     */
    suspend fun upsertItem(bucketId: Long?, draft: MediaItemDraft): MediaStoreItem

    suspend fun findItem(itemId: Long): MediaStoreItem?
    suspend fun findItemByUri(uri: Uri): MediaStoreItem?
    suspend fun findItemsByVideoId(videoId: String): List<MediaStoreItem>
    /** Items in [bucketId], or the orphan items (no bucket) when [bucketId] is null. */
    suspend fun itemsInBucket(bucketId: Long?): List<MediaStoreItem>

    /** Remove an item: soft-delete (tombstone) by default, or [hard] to purge it and its links now. */
    suspend fun deleteItem(itemId: Long, hard: Boolean = false)

    /** Hard-delete item tombstones whose deletion is older than [olderThan] (epoch millis). Returns the count removed. */
    suspend fun purgeDeletedItems(olderThan: Long): Int

    // ---- playlists ----

    suspend fun createPlaylist(name: String): Playlist
    suspend fun renamePlaylist(playlistId: Long, name: String)
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun playlists(): List<Playlist>

    /** Append the given items to the end of the playlist (existing memberships are ignored). */
    suspend fun addToPlaylist(playlistId: Long, itemIds: List<Long>)
    suspend fun removeFromPlaylist(playlistItemId: Long)

    /** Move a membership row to [toPosition], shifting the others to keep positions contiguous. */
    suspend fun movePlaylistItem(playlistItemId: Long, toPosition: Int)

    suspend fun playlistItems(playlistId: Long): List<PlaylistItem>

    /** Playlist rows joined with their (non-deleted) media items, in playlist order. */
    suspend fun playlistItemDetails(playlistId: Long): List<PlaylistItemDetail>

    suspend fun isFavorite(itemId: Long): Boolean

    // ---- reactive ----

    /** Emits whenever the items table changes. */
    fun itemsChanges(): Flow<Unit>

    /** Emits whenever the buckets table changes. */
    fun bucketsChanges(): Flow<Unit>

    /** Emits the ordered membership of [playlistId] initially and on every change. */
    fun playlistChanges(playlistId: Long): Flow<List<PlaylistItem>>

    companion object {
        const val FAVORITE_PLAYLIST_ID = 0L
        const val RECENT_PLAY_PLAYLIST_ID = -1L

        /** Build a store over an already-configured driver (schema created, foreign keys enabled). */
        fun create(driver: SqlDriver): BetterMediaStore = BetterMediaStoreImpl(driver)
    }
}
