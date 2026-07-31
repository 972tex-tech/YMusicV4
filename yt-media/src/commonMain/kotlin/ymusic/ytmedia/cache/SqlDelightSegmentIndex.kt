package ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.ytmedia.db.SegmentDatabase
import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.sabr.formatCacheKey

/**
 * SqlDelight-backed [SegmentIndex]. The [driver]'s schema must already be created
 * ([SegmentDatabase.Schema]); the app builds the platform driver (JDBC / Android / sqliter) and hands it
 * over, exactly like `DiskCacheStore`. Blocking SQLite runs on [ioDispatcher]; [now] supplies epoch-millis
 * `download_time` stamps (injectable for tests).
 */
class SqlDelightSegmentIndex(
    driver: SqlDriver,
    private val now: () -> Long,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SegmentIndex {

    private val queries = SegmentDatabase(driver).segmentQueries

    override suspend fun save(header: MediaHeader) = withContext(ioDispatcher) {
        // Legacy shape: init segment stores start_ms=0, end_ms=-1 (window degenerate); a real segment
        // stores [startMs, startMs+durationMs-1]. Byte range is always [startRange, startRange+len-1].
        val startMs = header.startMs ?: 0
        val endMs = startMs + (header.durationMs ?: 0) - 1
        queries.save(
            itag = header.itag.toLong(),
            lmt = header.lmt,
            seg_index = header.segmentIndex.toLong(),
            xtags = header.xtag,
            vid = header.videoId,
            start_ms = startMs,
            end_ms = endMs,
            r_start = header.startRange,
            r_end = header.startRange + header.contentLength - 1,
            download_time = now(),
        )
        Unit
    }

    override suspend fun findByStartTime(itag: Int, lmt: Long, playerMs: Long): MediaHeader? =
        withContext(ioDispatcher) {
            queries.findByStartTime(itag.toLong(), lmt, playerMs, ::toHeader).executeAsOneOrNull()
        }

    override suspend fun findBySegmentIndex(itag: Int, lmt: Long, seg: Int): MediaHeader? =
        withContext(ioDispatcher) {
            queries.findBySegmentIndex(itag.toLong(), lmt, seg.toLong(), ::toHeader).executeAsOneOrNull()
        }

    override suspend fun findByByteOffset(itag: Int, lmt: Long, byteIndex: Long): MediaHeader? =
        withContext(ioDispatcher) {
            queries.findByByteOffset(itag.toLong(), lmt, byteIndex, ::toHeader).executeAsOneOrNull()
        }

    override suspend fun markDownloadTime(itag: Int, lmt: Long): Unit = withContext(ioDispatcher) {
        queries.markDownloadTime(now(), itag.toLong(), lmt)
        Unit
    }

    override suspend fun cleanup(keepKeys: Set<String>) = withContext(ioDispatcher) {
        val cutoff = now() - retentionMillis
        queries.transaction {
            for (stream in queries.distinctStreams().executeAsList()) {
                if (formatCacheKey(stream.itag.toInt(), stream.lmt) !in keepKeys) {
                    queries.deleteStreamOlderThan(stream.itag, stream.lmt, cutoff)
                }
            }
        }
    }

    // SELECT * column order = table order. Reconstruct a MediaHeader: init (seg 0) has null time window;
    // a real segment's durationMs is (end_ms - start_ms + 1). headerId is synthetic on read (0).
    @Suppress("LongParameterList")
    private fun toHeader(
        itag: Long,
        lmt: Long,
        segIndex: Long,
        xtags: String?,
        vid: String,
        startMs: Long,
        endMs: Long,
        rStart: Long,
        rEnd: Long,
        downloadTime: Long,
    ): MediaHeader {
        val isInit = segIndex == 0L
        return MediaHeader(
            headerId = 0,
            videoId = vid,
            itag = itag.toInt(),
            lmt = lmt,
            xtag = xtags,
            startRange = rStart,
            isInitSegment = isInit,
            segmentIndex = segIndex.toInt(),
            startMs = if (isInit) null else startMs,
            durationMs = if (isInit) null else endMs - startMs + 1,
            contentLength = rEnd - rStart + 1,
        )
    }

    private companion object {
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days, as legacy
    }
}
