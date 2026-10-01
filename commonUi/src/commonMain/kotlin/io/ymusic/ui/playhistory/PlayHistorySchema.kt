package io.ymusic.ui.playhistory

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Schema for the watch-time ranking DB. Two tables, one row per local playlist-code / YouTube video. */
object PlayHistorySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
            CREATE TABLE local_play_history (
                code TEXT PRIMARY KEY NOT NULL,
                watch_time INTEGER NOT NULL,
                tracked_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX local_play_history_watch_time ON local_play_history(watch_time)", 0)
        driver.execute(null, "CREATE INDEX local_play_history_tracked_at ON local_play_history(tracked_at)", 0)
        driver.execute(
            null,
            """
            CREATE TABLE yt_play_history (
                video_id TEXT PRIMARY KEY NOT NULL,
                watch_time INTEGER NOT NULL,
                tracked_at INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                thumbnail_url TEXT,
                duration TEXT,
                duration_ms INTEGER,
                live INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX yt_play_history_watch_time ON yt_play_history(watch_time)", 0)
        driver.execute(null, "CREATE INDEX yt_play_history_tracked_at ON yt_play_history(tracked_at)", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}
