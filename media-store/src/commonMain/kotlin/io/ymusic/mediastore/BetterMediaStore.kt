package io.ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediastore.internal.BetterMediaStoreImpl
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.mediastore.model.MediaStoreBucket
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.mediastore.model.Playlist
import io.ymusic.mediastore.model.PlaylistItem
import io.ymusic.mediastore.model.PlaylistItemDetail
import kmpcommon.uri.Uri
import kotlinx.coroutines.flow.Flow

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

    /**
     * Exclude a folder: flag it, hard-delete its descendant subfolder rows, and soft-delete every
     * item in its subtree (kept as tombstones for playlist links and later revival). The folder's own
     * row stays (flagged) so the Folders tab can still show it (greyed) and offer re-inclusion, and
     * scans skip it (see [excludedBucketUris]). Passing `excluded = false` just clears the flag — the
     * contents come back on the next scan.
     */
    suspend fun setBucketExcluded(bucketId: Long, excluded: Boolean)

    /** URIs of the excluded folders (subtree roots), for the scanner to skip. */
    suspend fun excludedBucketUris(): Set<Uri>

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

    /** Every non-deleted item (orphans + all buckets), title-ordered — one query, not one per bucket. */
    suspend fun allItems(): List<MediaStoreItem>

    /** Remove an item: soft-delete (tombstone) by default, or [hard] to purge it and its links now. */
    suspend fun deleteItem(itemId: Long, hard: Boolean = false)

    /** Hard-delete item tombstones whose deletion is older than [olderThan] (epoch millis). Returns the count removed. */
    suspend fun purgeDeletedItems(olderThan: Long): Int

    // ---- playlists ----

    suspend fun createPlaylist(name: String): Playlist
    suspend fun renamePlaylist(playlistId: Long, name: String)
    suspend fun deletePlaylist(playlistId: Long)
    suspend fun playlists(): List<Playlist>

    /** Append items to the end of the playlist by their [MediaStoreItem.playlistCode] (existing members ignored). */
    suspend fun addToPlaylist(playlistId: Long, items: List<MediaStoreItem>)
    suspend fun removeFromPlaylist(playlistId: Long, code: String)

    /** Replace the playlist's order with [orderedCodes] (the codes already in it, in the new order). */
    suspend fun reorderPlaylist(playlistId: Long, orderedCodes: List<String>)

    suspend fun playlistItems(playlistId: Long): List<PlaylistItem>

    /** Playlist rows joined with the (non-deleted) media item each [PlaylistItem.code] resolves to, in order. */
    suspend fun playlistItemDetails(playlistId: Long): List<PlaylistItemDetail>

    // ---- system (computed) playlists ----
    //
    // Recent play / Last added / Top tracks aren't rows in the playlist table — they're live queries
    // over the media items, so they need no membership upkeep and never go stale.

    /** Record a play of the library item [itemId] (bumps its play count + last-played time). Keyed on
     *  the row id — not the uri — so it survives a move/rescan rewriting the file's uri; a no-op for a
     *  stale/unknown id, so it's safe to call for anything that isn't a live library row. */
    suspend fun recordLocalPlay(itemId: Long)

    /** "Recent play": items by most-recent play, newest first (only items played at least once). */
    suspend fun recentlyPlayed(limit: Int = 200): List<MediaStoreItem>

    /** "Top tracks": the most-played items first (only items played at least once). */
    suspend fun topTracks(limit: Int = 200): List<MediaStoreItem>

    // ---- reactive ----

    /** Emits whenever the items table changes. */
    fun itemsChanges(): Flow<Unit>

    /** Emits whenever the buckets table changes. */
    fun bucketsChanges(): Flow<Unit>

    /** Emits the ordered membership of [playlistId] initially and on every change. */
    fun playlistChanges(playlistId: Long): Flow<List<PlaylistItem>>

    companion object {
        /** Build a store over an already-configured driver (schema created, foreign keys enabled). */
        fun create(driver: SqlDriver): BetterMediaStore = BetterMediaStoreImpl(driver)
    }
}
