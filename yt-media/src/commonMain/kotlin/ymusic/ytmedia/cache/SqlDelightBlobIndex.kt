package ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import kmpcommon.WorkerThread
import ymusic.ytmedia.db.SegmentDatabase

/**
 * SqlDelight-backed [BlobIndex] (`BlobSpan.sq` on [SegmentDatabase]). The [driver]'s schema must already
 * be created ([SegmentDatabase.Schema]); the app builds the platform driver (JDBC / Android / sqliter) and
 * typically shares it with [SqlDelightSegmentIndex] so all cache metadata lives in one DB. Synchronous —
 * SqlDelight queries block, which is exactly the non-suspend [BlobIndex] contract; [DiskMediaBlobCache]
 * calls these under its own lock.
 */
@WorkerThread
class SqlDelightBlobIndex(driver: SqlDriver) : BlobIndex {

    private val queries = SegmentDatabase(driver).blobSpanQueries

    override fun maxLastUsed(): Long = queries.maxLastUsed().executeAsOne()

    override fun spansForKey(key: String): List<BlobSpan> =
        queries.spansForKey(key) { start, len -> BlobSpan(key, start, len) }.executeAsList()

    override fun insert(key: String, start: Long, len: Long, lastUsed: Long) {
        queries.insertSpan(key, start, len, lastUsed)
    }

    override fun touch(key: String, start: Long, len: Long, lastUsed: Long) {
        queries.touch(lastUsed, key, start, len)
    }

    override fun delete(key: String, start: Long, len: Long) {
        queries.deleteSpan(key, start, len)
    }

    override fun totalSize(): Long = queries.totalSize().executeAsOne()

    override fun distinctKeys(): Set<String> = queries.distinctKeys().executeAsList().toSet()

    override fun lruOrder(): List<BlobSpan> =
        queries.lruOrder { key, start, len -> BlobSpan(key, start, len) }.executeAsList()
}
