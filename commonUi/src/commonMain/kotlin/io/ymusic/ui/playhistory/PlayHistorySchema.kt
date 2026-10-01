package io.ymusic.ui.playhistory

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Watch-time tables are accumulators; [yt_video_metadata] is a display snapshot for the YouTube
 * chart (legacy split: YtPlayHistoryDb vs YtVideoMetadataDb).
 */
object PlayHistorySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = 2

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.exec(
            """
            CREATE TABLE local_play_history (
                code TEXT PRIMARY KEY NOT NULL,
                watch_time INTEGER NOT NULL,
                tracked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        driver.exec("CREATE INDEX local_play_history_watch_time ON local_play_history(watch_time)")
        driver.exec("CREATE INDEX local_play_history_tracked_at ON local_play_history(tracked_at)")
        createYtHistory(driver)
        createYtMetadata(driver)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        if (oldVersion < 2) {
            createYtMetadata(driver)
            driver.exec(
                """
                INSERT INTO yt_video_metadata(
                    video_id, title, artist, thumbnail_url, duration, duration_ms, last_updated
                )
                SELECT video_id, title, artist, thumbnail_url, duration, duration_ms, tracked_at
                FROM yt_play_history
                WHERE live = 0
                """.trimIndent(),
            )
            driver.exec(
                """
                CREATE TABLE yt_play_history_new (
                    video_id TEXT PRIMARY KEY NOT NULL,
                    watch_time INTEGER NOT NULL,
                    tracked_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            driver.exec(
                """
                INSERT INTO yt_play_history_new(video_id, watch_time, tracked_at)
                SELECT video_id, watch_time, tracked_at FROM yt_play_history
                """.trimIndent(),
            )
            driver.exec("DROP TABLE yt_play_history")
            driver.exec("ALTER TABLE yt_play_history_new RENAME TO yt_play_history")
            driver.exec("CREATE INDEX yt_play_history_watch_time ON yt_play_history(watch_time)")
            driver.exec("CREATE INDEX yt_play_history_tracked_at ON yt_play_history(tracked_at)")
        }
        return QueryResult.Unit
    }

    private fun createYtHistory(driver: SqlDriver) {
        driver.exec(
            """
            CREATE TABLE yt_play_history (
                video_id TEXT PRIMARY KEY NOT NULL,
                watch_time INTEGER NOT NULL,
                tracked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        driver.exec("CREATE INDEX yt_play_history_watch_time ON yt_play_history(watch_time)")
        driver.exec("CREATE INDEX yt_play_history_tracked_at ON yt_play_history(tracked_at)")
    }

    private fun createYtMetadata(driver: SqlDriver) {
        driver.exec(
            """
            CREATE TABLE yt_video_metadata (
                video_id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                thumbnail_url TEXT,
                duration TEXT,
                duration_ms INTEGER,
                last_updated INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        driver.exec("CREATE INDEX yt_video_metadata_last_updated ON yt_video_metadata(last_updated)")
    }
}

private fun SqlDriver.exec(sql: String) {
    execute(null, sql, 0)
}
