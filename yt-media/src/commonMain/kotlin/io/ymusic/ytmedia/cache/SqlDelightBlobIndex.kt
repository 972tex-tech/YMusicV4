package io.ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.ytmedia.db.SegmentDatabase
import kmpcommon.WorkerThread

/**
 * SqlDelight-backed [BlobIndex] (`BlobSpan.sq` on [SegmentDatabase]). The [driver]'s schema must already
 * be created ([SegmentDatabase.Schema]); the app builds the platform driver (JDBC / Android / sqliter) and
 * typically shares it with [SqlDelightSegmentIndex] so all cache metadata lives in one DB. Synchronous —
 * SqlDelight queries block, which is exactly the non-suspend [BlobIndex] contract; [DiskMediaBlobCache]
 * calls these under its own lock.
 *
 * `last_used` is plain wall-clock epoch-millis from [now] (like `SqlDelightSegmentIndex.download_time`) —
 * good enough for LRU order and directly usable for age-based retention. Clock drift / same-millisecond
 * ties are ignored (LRU just orders those arbitrarily).
 */
@WorkerThread
class SqlDelightBlobIndex(driver: SqlDriver, private val now: () -> Long) : BlobIndex {

    private val queries = SegmentDatabase(driver).blobSpanQueries

    override fun spansForKey(key: String): List<BlobSpan> =
        queries.spansForKey(key) { start, len -> BlobSpan(key, start, len) }.executeAsList()

    override fun insert(key: String, start: Long, len: Long) {
        queries.insertSpan(key, start, len, now())
    }

    override fun touch(key: String, start: Long, len: Long) {
        queries.touch(now(), key, start, len)
    }

    override fun delete(key: String, start: Long, len: Long) {
        queries.deleteSpan(key, start, len)
    }

    override fun totalSize(): Long = queries.totalSize().executeAsOne()

    override fun distinctKeys(): Set<String> = queries.distinctKeys().executeAsList().toSet()

    override fun lruOrder(): List<BlobSpan> =
        queries.lruOrder { key, start, len -> BlobSpan(key, start, len) }.executeAsList()
}
