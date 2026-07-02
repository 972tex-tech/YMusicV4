package ymusic.mediastore.internal

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.db.MediaStoreDatabase
import ymusic.mediastore.model.BucketDraft
import ymusic.mediastore.model.MediaItemDraft
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist
import ymusic.mediastore.model.PlaylistItem
import ymusic.mediastore.model.PlaylistItemDetail

internal class BetterMediaStoreImpl(
    private val driver: SqlDriver,
    // storeDispatcher() confines all DB access to a single dedicated thread, so the store uses one
    // connection to one database instance no matter how concurrently it's driven (the scanner uses a
    // worker pool). Required by JdbcSqliteDriver's thread-local connection, and the simplest correct
    // model on every platform — SQLite serializes writers anyway.
    private val io: CoroutineDispatcher = storeDispatcher(),
) : BetterMediaStore {

    private val db = MediaStoreDatabase(driver)
    private val bucketQ = db.bucketQueries
    private val itemQ = db.mediaItemQueries
    private val playlistQ = db.playlistQueries
    private val plItemQ = db.playlistItemQueries

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    // ---- buckets ----

    override suspend fun upsertBucket(draft: BucketDraft): MediaStoreBucket = withContext(io) {
        db.transactionWithResult {
            val uri = draft.uri.toString()
            val existing = bucketQ.findBucketByUriIncludingDeleted(uri).executeAsOneOrNull()
            val ts = now()
            val id = if (existing != null) {
                bucketQ.updateBucket(draft.parentId, draft.name, draft.aliasName, ts, existing.id)
                existing.id
            } else {
                bucketQ.insertBucket(draft.parentId, uri, draft.name, draft.aliasName, ts, ts)
                bucketQ.lastInsertRowId().executeAsOne()
            }
            bucketQ.findBucketById(id).executeAsOne().toModel()
        }
    }

    override suspend fun setBucketAlias(bucketId: Long, alias: String?): Unit = withContext(io) {
        bucketQ.setBucketAlias(alias, now(), bucketId)
    }

    override suspend fun findBucket(bucketId: Long): MediaStoreBucket? = withContext(io) {
        bucketQ.findBucketById(bucketId).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findBucketByUri(uri: Uri): MediaStoreBucket? = withContext(io) {
        bucketQ.findBucketByUri(uri.toString()).executeAsOneOrNull()?.toModel()
    }

    override suspend fun childBuckets(parentId: Long?): List<MediaStoreBucket> = withContext(io) {
        val rows = if (parentId == null) bucketQ.rootBuckets() else bucketQ.childBuckets(parentId)
        rows.executeAsList().map { it.toModel() }
    }

    override suspend fun allBuckets(): List<MediaStoreBucket> = withContext(io) {
        bucketQ.allBuckets().executeAsList().map { it.toModel() }
    }

    override suspend fun deleteBucket(bucketId: Long, hard: Boolean): Unit = withContext(io) {
        db.transaction {
            if (hard) {
                // FK ON DELETE CASCADE removes child buckets, items, and their playlist links.
                bucketQ.hardDeleteBucket(bucketId)
            } else {
                // Soft delete mirrors the hard-delete cascade: tombstone the whole subtree, so a
                // removed folder never leaves orphaned descendant buckets/items live in queries.
                softDeleteSubtree(bucketId, now())
            }
        }
    }

    private fun softDeleteSubtree(bucketId: Long, ts: Long) {
        bucketQ.childBuckets(bucketId).executeAsList().forEach { child ->
            softDeleteSubtree(child.id, ts)
        }
        itemQ.setItemsInBucketDeleted(ts, bucketId)
        bucketQ.setBucketDeleted(1, ts, bucketId)
    }

    // ---- items ----

    override suspend fun upsertItem(bucketId: Long?, draft: MediaItemDraft): MediaStoreItem =
        withContext(io) {
            db.transactionWithResult {
                val uri = draft.uri.toString()
                val ts = now()
                val existingByUri = itemQ.findItemByUriIncludingDeleted(uri).executeAsOneOrNull()
                val id = when {
                    existingByUri != null -> {
                        updateItem(bucketId, draft, ts, existingByUri.id)
                        existingByUri.id
                    }
                    else -> {
                        // Move detection: a soft-deleted twin in the same bucket by fileSize.
                        val moved = itemQ.findDeletedByFingerprint(bucketId, draft.fileSize)
                            .executeAsList().firstOrNull()
                        if (moved != null) {
                            updateItem(bucketId, draft, ts, moved.id)
                            moved.id
                        } else {
                            insertItem(bucketId, draft, ts)
                            itemQ.lastInsertRowId().executeAsOne()
                        }
                    }
                }
                itemQ.findItemById(id).executeAsOne().toModel()
            }
        }

    private fun insertItem(bucketId: Long?, d: MediaItemDraft, ts: Long) = itemQ.insertItem(
        bucketId = bucketId,
        uri = d.uri.toString(),
        title = d.title,
        mimeType = d.mimeType,
        artist = d.artist,
        album = d.album,
        trackNumber = d.trackNumber?.toLong(),
        year = d.year?.toLong(),
        durationMs = d.durationMs,
        audioCodec = d.audioCodec,
        videoCodec = d.videoCodec,
        subtitleCodec = d.subtitleCodec,
        bitrate = d.bitrate?.toLong(),
        width = d.width?.toLong(),
        height = d.height?.toLong(),
        thumbnailStreamIndex = d.thumbnailStreamIndex?.toLong(),
        youtubeVid = d.youtubeVid,
        youtubeItags = d.youtubeItags,
        fileSize = d.fileSize,
        lastModified = d.lastModified,
        createdAt = ts,
        updatedAt = ts,
    )

    private fun updateItem(bucketId: Long?, d: MediaItemDraft, ts: Long, id: Long) = itemQ.updateItem(
        bucketId = bucketId,
        uri = d.uri.toString(),
        title = d.title,
        mimeType = d.mimeType,
        artist = d.artist,
        album = d.album,
        trackNumber = d.trackNumber?.toLong(),
        year = d.year?.toLong(),
        durationMs = d.durationMs,
        audioCodec = d.audioCodec,
        videoCodec = d.videoCodec,
        subtitleCodec = d.subtitleCodec,
        bitrate = d.bitrate?.toLong(),
        width = d.width?.toLong(),
        height = d.height?.toLong(),
        thumbnailStreamIndex = d.thumbnailStreamIndex?.toLong(),
        youtubeVid = d.youtubeVid,
        youtubeItags = d.youtubeItags,
        fileSize = d.fileSize,
        lastModified = d.lastModified,
        updatedAt = ts,
        id = id,
    )

    override suspend fun findItem(itemId: Long): MediaStoreItem? = withContext(io) {
        itemQ.findItemById(itemId).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findItemByUri(uri: Uri): MediaStoreItem? = withContext(io) {
        itemQ.findItemByUri(uri.toString()).executeAsOneOrNull()?.toModel()
    }

    override suspend fun findItemsByVideoId(videoId: String): List<MediaStoreItem> = withContext(io) {
        itemQ.findItemByVideoId(videoId).executeAsList().map { it.toModel() }
    }

    override suspend fun itemsInBucket(bucketId: Long?): List<MediaStoreItem> = withContext(io) {
        val rows = if (bucketId == null) itemQ.orphanItems() else itemQ.itemsInBucket(bucketId)
        rows.executeAsList().map { it.toModel() }
    }

    override suspend fun deleteItem(itemId: Long, hard: Boolean): Unit = withContext(io) {
        if (hard) itemQ.hardDeleteItem(itemId) else itemQ.setItemDeleted(now(), itemId)
    }

    override suspend fun purgeDeletedItems(olderThan: Long): Int = withContext(io) {
        // The generated DELETE returns the number of affected rows.
        itemQ.purgeDeleted(olderThan).value.toInt()
    }

    // ---- playlists ----

    override suspend fun createPlaylist(name: String): Playlist = withContext(io) {
        db.transactionWithResult {
            val ts = now()
            playlistQ.insertPlaylist(name, ts, ts)
            val id = playlistQ.lastInsertRowId().executeAsOne()
            playlistQ.findPlaylist(id).executeAsOne().toModel()
        }
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String): Unit = withContext(io) {
        playlistQ.renamePlaylist(name, now(), playlistId)
    }

    override suspend fun deletePlaylist(playlistId: Long): Unit = withContext(io) {
        require(playlistId != BetterMediaStore.FAVORITE_PLAYLIST_ID &&
            playlistId != BetterMediaStore.RECENT_PLAY_PLAYLIST_ID) {
            "Cannot delete the reserved playlist $playlistId"
        }
        playlistQ.deletePlaylist(playlistId)
    }

    override suspend fun playlists(): List<Playlist> = withContext(io) {
        playlistQ.allPlaylists().executeAsList().map { it.toModel() }
    }

    override suspend fun addToPlaylist(playlistId: Long, itemIds: List<Long>): Unit = withContext(io) {
        if (itemIds.isEmpty()) return@withContext
        db.transaction {
            val existing = plItemQ.playlistMediaItemIds(playlistId).executeAsList().toHashSet()
            var pos = plItemQ.maxPosition(playlistId).executeAsOne() // COALESCE(MAX(position), -1)
            val ts = now()
            for (itemId in itemIds) {
                if (!existing.add(itemId)) continue // already a member (also de-dups the input list)
                pos += 1
                plItemQ.insertPlaylistItem(playlistId, itemId, pos, ts)
            }
        }
    }

    override suspend fun removeFromPlaylist(playlistItemId: Long): Unit = withContext(io) {
        plItemQ.deletePlaylistItem(playlistItemId)
    }

    override suspend fun movePlaylistItem(playlistItemId: Long, toPosition: Int): Unit =
        withContext(io) {
            db.transaction {
                val row = plItemQ.findPlaylistItem(playlistItemId).executeAsOneOrNull()
                    ?: return@transaction
                // Reorder within the item's playlist, then rewrite contiguous positions 0..n-1.
                val ordered = plItemQ.playlistItemsOrdered(row.playlistId).executeAsList()
                val moving = ordered.first { it.id == playlistItemId }
                val without = ordered.filter { it.id != playlistItemId }
                val target = toPosition.coerceIn(0, without.size)
                val reordered = without.toMutableList().apply { add(target, moving) }
                reordered.forEachIndexed { index, item ->
                    if (item.position != index.toLong()) {
                        plItemQ.updatePlaylistItemPosition(index.toLong(), item.id)
                    }
                }
            }
        }

    override suspend fun playlistItems(playlistId: Long): List<PlaylistItem> = withContext(io) {
        plItemQ.playlistItemsOrdered(playlistId).executeAsList().map { it.toModel() }
    }

    override suspend fun playlistItemDetails(playlistId: Long): List<PlaylistItemDetail> =
        withContext(io) {
            val rows = plItemQ.playlistItemsOrdered(playlistId).executeAsList()
            if (rows.isEmpty()) return@withContext emptyList()
            val itemsById = itemQ.itemsByIds(rows.map { it.mediaItemId })
                .executeAsList().associateBy { it.id }
            rows.mapNotNull { row ->
                val item = itemsById[row.mediaItemId] ?: return@mapNotNull null
                PlaylistItemDetail(
                    playlistItemId = row.id,
                    playlistId = row.playlistId,
                    position = row.position.toInt(),
                    addedAt = row.addedAt,
                    mediaItem = item.toModel(),
                )
            }
        }

    override suspend fun isFavorite(itemId: Long): Boolean = withContext(io) {
        plItemQ.isFavorite(itemId).executeAsOne()
    }

    // ---- reactive ----

    override fun itemsChanges(): Flow<Unit> = tableChanges("mediaItem")
    override fun bucketsChanges(): Flow<Unit> = tableChanges("bucket")

    override fun playlistChanges(playlistId: Long): Flow<List<PlaylistItem>> =
        plItemQ.playlistItemsOrdered(playlistId)
            .asFlow()
            .mapToList(io)
            .map { rows -> rows.map { it.toModel() } }

    private fun tableChanges(table: String): Flow<Unit> = callbackFlow {
        val listener = Query.Listener { trySend(Unit) }
        driver.addListener(table, listener = listener)
        awaitClose { driver.removeListener(table, listener = listener) }
    }
}
