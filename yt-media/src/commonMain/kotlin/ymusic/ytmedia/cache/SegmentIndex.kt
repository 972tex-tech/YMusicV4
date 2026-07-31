package ymusic.ytmedia.cache

import ymusic.ytmedia.sabr.MediaHeader

/**
 * The segment ⇄ (timeRange, byteRange) map SABR planning needs on top of the raw bytes in
 * [MediaBlobCache]. A **queryable hint**, not the source of truth: a row can exist while the segment's
 * bytes are still being written or were lost to a crash, so a planner always verifies a hit against
 * `blobs.isCached` before serving it (plan §3b). Port of legacy `SabrCacheDb`.
 *
 * All calls are blocking SQLite under the hood — suspend so implementations run them off the main thread.
 */
interface SegmentIndex {

    /** Record a segment (INSERT-OR-IGNORE: the first writer of a `(itag, lmt, seg)` wins). */
    suspend fun save(header: MediaHeader)

    /** The segment whose player-time window contains [playerMs], or null. */
    suspend fun findByStartTime(itag: Int, lmt: Long, playerMs: Long): MediaHeader?

    suspend fun findBySegmentIndex(itag: Int, lmt: Long, seg: Int): MediaHeader?

    /** Downloader/seek resume: the furthest segment ending at or before [byteIndex], or null. */
    suspend fun findByByteOffset(itag: Int, lmt: Long, byteIndex: Long): MediaHeader?

    /** Bump the `download_time` of every segment of a stream (used to protect a just-downloaded track). */
    suspend fun markDownloadTime(itag: Int, lmt: Long)

    /**
     * Prune orphan rows: any stream whose `formatCacheKey` is **not** in [keepKeys] (i.e. has no blobs)
     * and whose segments are older than the retention window is deleted. Run on startup with
     * `keepKeys = blobs.keys()` — this one rule keeps the index and the blob store consistent without a
     * cross-store transaction (plan §3b).
     */
    suspend fun cleanup(keepKeys: Set<String>)
}
