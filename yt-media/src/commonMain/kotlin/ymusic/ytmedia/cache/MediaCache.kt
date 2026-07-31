package ymusic.ytmedia.cache

import okio.BufferedSink
import ymusic.ytmedia.sabr.MediaHeader

/**
 * The facade the downloader and (later) the player-interception layer both use to fill and serve the
 * player cache. It ties the byte substrate ([blobs]) to the segment metadata ([index]) so one stored
 * copy of a segment's bytes answers progressive / UMP / SABR reads alike (plan §3c).
 *
 * This step wires the **fill** side into the SABR downloader ([openSegmentFill]); serving
 * (`serveSabr` / `serveProgressive`) lands in the following steps.
 */
class MediaCache(
    val blobs: MediaBlobCache,
    val index: SegmentIndex,
) {
    /**
     * Begin mirroring one segment into the cache. The metadata row is saved **now** (on the MEDIA_HEADER,
     * a queryable hint) while the bytes are committed later on [SegmentFill.commit] (the MEDIA_END, the
     * truth) — so a crash in between leaves an orphan row that `index.cleanup(blobs.keys())` prunes, with
     * no cross-store transaction (plan §3b). The returned writer is a no-op if these bytes are already
     * cached or being written elsewhere.
     */
    suspend fun openSegmentFill(header: MediaHeader): SegmentFill {
        index.save(header)
        return SegmentFill(blobs.writer(header.cacheKey, header.startRange, header.contentLength))
    }
}

/** A one-segment mirror handle: write the segment's bytes to [sink], then [commit] (or [abort]).
 *  [commit]/[abort] do blocking IO on the calling thread (see [MediaBlobCache]) — call off the main thread. */
class SegmentFill internal constructor(private val writer: BlobWriter) {
    val sink: BufferedSink get() = writer.sink
    fun commit() = writer.commit()
    fun abort() = writer.abort()
}
