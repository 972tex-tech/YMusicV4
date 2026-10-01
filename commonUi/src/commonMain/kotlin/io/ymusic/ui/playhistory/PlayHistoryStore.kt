package io.ymusic.ui.playhistory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.ui.youtubeThumbUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Watch-time ranking for Library Recent play / Top tracks and Discover Top tracks.
 *
 * Local rows are keyed by [MediaStoreItem.playlistCode] (YouTube id or `@fileSize:durationSec`) so a
 * move/rescan that keeps the file still ranks. YouTube rows are keyed by video id and carry the
 * display fields the Discover chart needs.
 */
class PlayHistoryStore(
    private val driver: SqlDriver,
    private val retain: Int = DEFAULT_RETAIN,
) {
    private val io = playHistoryDispatcher()
    private var cleaned = false

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: Flow<Unit> = _changes.asSharedFlow()

    suspend fun trackLocal(code: String, watchTimeMs: Long) {
        require(watchTimeMs >= 0)
        if (watchTimeMs == 0L || code.isEmpty()) return
        withContext(io) {
            cleanupIfNeeded()
            upsertLocal(code, watchTimeMs)
        }
        _changes.tryEmit(Unit)
    }

    suspend fun trackYoutube(
        videoId: String,
        watchTimeMs: Long,
        title: String,
        artist: String? = null,
        thumbnailUrl: String? = null,
        duration: String? = null,
        durationMs: Long? = null,
        live: Boolean = false,
    ) {
        require(watchTimeMs >= 0)
        if (watchTimeMs == 0L || videoId.isEmpty()) return
        withContext(io) {
            cleanupIfNeeded()
            upsertYoutube(videoId, watchTimeMs, title, artist, thumbnailUrl, duration, durationMs, live)
        }
        _changes.tryEmit(Unit)
    }

    suspend fun localTopChart(limit: Int = retain): List<String> = withContext(io) {
        queryIds(
            "SELECT code FROM local_play_history ORDER BY watch_time DESC, tracked_at DESC LIMIT ?",
            limit,
        )
    }

    suspend fun localRecent(limit: Int = retain): List<String> = withContext(io) {
        queryIds(
            "SELECT code FROM local_play_history ORDER BY tracked_at DESC LIMIT ?",
            limit,
        )
    }

    suspend fun youtubeTopChart(limit: Int = retain): List<YtFeedItem> = withContext(io) {
        driver.executeQuery(
            null,
            """
            SELECT video_id, title, artist, thumbnail_url, duration, duration_ms, live
            FROM yt_play_history
            ORDER BY watch_time DESC, tracked_at DESC
            LIMIT ?
            """.trimIndent(),
            { cursor ->
                val out = mutableListOf<YtFeedItem>()
                while (cursor.next().value) {
                    val videoId = cursor.getString(0)!!
                    val thumb = cursor.getString(3)
                    out += YtFeedItem(
                        kind = YtFeedItem.KIND_VIDEO,
                        id = videoId,
                        title = cursor.getString(1)!!,
                        subtitleLine1 = cursor.getString(2),
                        thumbs = listOf(YtThumb(thumb ?: youtubeThumbUrl(videoId))),
                        duration = cursor.getString(4),
                        durationMs = cursor.getLong(5),
                        live = (cursor.getLong(6) ?: 0L) != 0L,
                    )
                }
                QueryResult.Value(out)
            },
            1,
        ) { bindLong(0, limit.toLong()) }.value
    }

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun upsertLocal(code: String, watchTimeMs: Long) {
        val ts = now()
        driver.execute(
            null,
            """
            INSERT INTO local_play_history(code, watch_time, tracked_at) VALUES (?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                watch_time = watch_time + excluded.watch_time,
                tracked_at = excluded.tracked_at
            """.trimIndent(),
            3,
        ) {
            bindString(0, code)
            bindLong(1, watchTimeMs)
            bindLong(2, ts)
        }
    }

    private fun upsertYoutube(
        videoId: String,
        watchTimeMs: Long,
        title: String,
        artist: String?,
        thumbnailUrl: String?,
        duration: String?,
        durationMs: Long?,
        live: Boolean,
    ) {
        val ts = now()
        driver.execute(
            null,
            """
            INSERT INTO yt_play_history(
                video_id, watch_time, tracked_at, title, artist, thumbnail_url, duration, duration_ms, live
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(video_id) DO UPDATE SET
                watch_time = watch_time + excluded.watch_time,
                tracked_at = excluded.tracked_at,
                title = excluded.title,
                artist = excluded.artist,
                thumbnail_url = excluded.thumbnail_url,
                duration = excluded.duration,
                duration_ms = excluded.duration_ms,
                live = excluded.live
            """.trimIndent(),
            9,
        ) {
            bindString(0, videoId)
            bindLong(1, watchTimeMs)
            bindLong(2, ts)
            bindString(3, title)
            bindString(4, artist)
            bindString(5, thumbnailUrl)
            bindString(6, duration)
            bindLong(7, durationMs)
            bindLong(8, if (live) 1L else 0L)
        }
    }

    private fun queryIds(sql: String, limit: Int): List<String> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val out = mutableListOf<String>()
                while (cursor.next().value) {
                    out += cursor.getString(0)!!
                }
                QueryResult.Value(out)
            },
            1,
        ) { bindLong(0, limit.toLong()) }.value

    private fun cleanupIfNeeded() {
        if (cleaned) return
        cleaned = true
        cleanupTable("local_play_history", "code")
        cleanupTable("yt_play_history", "video_id")
    }

    private fun cleanupTable(table: String, idColumn: String) {
        driver.execute(
            null,
            """
            DELETE FROM $table
            WHERE $idColumn IN (
                SELECT $idColumn FROM $table ORDER BY tracked_at DESC LIMIT 999 OFFSET ?
            )
            """.trimIndent(),
            1,
        ) { bindLong(0, retain.toLong()) }
    }

    companion object {
        const val DEFAULT_RETAIN = 200
    }
}

/** [codes] order (chart / recency) mapped onto the current library; missing files drop out. */
fun mediaItemsByPlayHistoryCodes(
    library: List<MediaStoreItem>,
    codes: List<String>,
): List<MediaStoreItem> {
    val map = library.associateBy { it.playlistCode }
    return codes.mapNotNull { map[it] }
}
