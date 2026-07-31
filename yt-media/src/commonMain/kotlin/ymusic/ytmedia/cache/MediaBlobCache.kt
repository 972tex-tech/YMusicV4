package ymusic.ytmedia.cache

import kmpcommon.WorkerThread
import okio.BufferedSink
import okio.Source

/**
 * The byte-range substrate under the player cache: "give me / here are bytes `[position, position+length)`
 * of stream [key]". One [key] is a format stream (`formatCacheKey(itag, lmt)` = `"2_{itag}_{lmt}"`), and
 * because a format's segments are contiguous in byte space, the same stored bytes answer a progressive
 * `range=` read, a UMP GET, and a synthesized SABR segment alike (see `docs/player/sabr-cache-plan.md` §1).
 *
 * The store is deliberately positional (unlike `CacheStore`, which is keyed-blob with no seek): committed
 * bytes live in **immutable span files** `{root}/{key}/{start}_{len}.span`, so reads never see a torn write.
 * The interesting part is the concurrency model reproduced from media3's `SimpleCache` by hand — atomic
 * commit, per-block resume, single-writer dedupe, read pinning, plan-then-reacquire, LRU — documented on
 * [DiskMediaBlobCache]. The in-memory span index is guarded internally, so concurrent calls from multiple
 * worker threads are safe.
 */
@WorkerThread
interface MediaBlobCache {

    /**
     * Does the union of committed spans fully cover `[position, position+length)` **right now**? A
     * planning *hint* only — never a lock. A true here can become a miss by the time [read] runs (a race
     * with eviction), which is exactly why callers plan with this and then re-acquire with [read],
     * treating a null there as a network miss (plan-then-reacquire).
     */
    fun isCached(key: String, position: Long, length: Long): Boolean

    /**
     * Pin the spans covering `[position, position+length)` and return an immutable, stitched [Source] over
     * exactly those bytes — or **null if not fully covered at this instant**. Pinned spans are never
     * evicted while the returned source is open; the caller **must close it** to unpin. A null result is
     * the signal to fall through to the network.
     */
    fun read(key: String, position: Long, length: Long): PinnedSource?

    /**
     * A writer for one region `[position, position+length)`. Bytes stream to [BlobWriter.sink]; the caller
     * ends with [BlobWriter.commit] (persist the trailing partial block) or [BlobWriter.abort] (drop it).
     * Returns a **no-op writer** when the exact region is already committed or already being written by
     * another worker — so player readahead and a download fetching the same segment never double-write
     * or corrupt each other.
     */
    fun writer(key: String, position: Long, length: Long): BlobWriter

    /** Every stream key with at least one committed span — the `keepKeys` set for `SegmentIndex.cleanup`. */
    fun keys(): Set<String>

    /** Total bytes of all committed spans (the LRU budget is measured against this). */
    fun totalSize(): Long
}

/** A stitched read over pinned, immutable spans. Closing it unpins them (idempotent). */
interface PinnedSource : Source

/**
 * A single region's writer. [sink] takes the region's bytes; the cache spills them to a temp file and
 * commits immutable span blocks as they fill. Finish with exactly one of [commit] / [abort]; both are
 * idempotent and release the in-flight lock for the region.
 */
@WorkerThread
interface BlobWriter {
    val sink: BufferedSink

    /** Commit the trailing partial block and release the region. Blocks already committed mid-stream stay. */
    fun commit()

    /** Drop the uncommitted temp block and release the region. Blocks committed mid-stream still stay
     *  (they are valid cached bytes for their own ranges — the point of per-block commit). */
    fun abort()
}
