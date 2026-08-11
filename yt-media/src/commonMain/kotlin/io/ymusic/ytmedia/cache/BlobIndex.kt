package io.ymusic.ytmedia.cache

import kmpcommon.WorkerThread

/** One cached byte range as the index knows it: `[start, start+len)` of stream [key]. */
class BlobSpan(val key: String, val start: Long, val len: Long) {
    val end: Long get() = start + len
}

/**
 * The persistent index of committed blob spans behind [DiskMediaBlobCache] — the source of truth for
 * which byte ranges are cached, the total cached size, and LRU recency (`last_used`). Kept as an interface
 * so the byte store is decoupled from its metadata backing; [SqlDelightBlobIndex] is the SQLite one.
 *
 * Non-suspend / [WorkerThread], like [MediaBlobCache] itself: the cache calls these inside its own lock on
 * a worker thread, so none of them may assume the main thread. A row is a coverage HINT — [MediaBlobCache]
 * verifies the file exists before serving and drops a stale row on a miss.
 */
@WorkerThread
interface BlobIndex {
    /** Every committed span of [key], **sorted by `start` ascending** (the cache's cover sweep depends
     *  on it). */
    fun spansForKey(key: String): List<BlobSpan>

    /** Record a committed span, stamping it accessed-now. First writer of an exact `(key, start, len)`
     *  wins; a re-insert is a no-op. */
    fun insert(key: String, start: Long, len: Long)

    /** Bump a span's recency to now (the LRU touch on read). */
    fun touch(key: String, start: Long, len: Long)

    /** Drop a span row — on eviction, or when its file is found missing. */
    fun delete(key: String, start: Long, len: Long)

    /** Total bytes of all committed spans; the LRU budget is measured against this. */
    fun totalSize(): Long

    /** Every stream key with at least one committed span. */
    fun distinctKeys(): Set<String>

    /** All spans in ascending recency — least-recently-used first, i.e. eviction order. */
    fun lruOrder(): List<BlobSpan>
}
