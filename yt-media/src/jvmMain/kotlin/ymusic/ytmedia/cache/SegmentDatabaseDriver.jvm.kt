package ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ymusic.ytmedia.db.SegmentDatabase

/**
 * Build the JVM (JDBC) [SqlDriver] for [SqlDelightSegmentIndex] with the schema created. Pass a
 * `jdbc:sqlite:/path/segments.db` URL for a persistent index, or the default in-memory URL for tests /
 * throwaway runs. Keeps the sqldelight-jdbc dependency inside `:yt-media` — callers only see [SqlDriver].
 */
fun jvmSegmentDatabaseDriver(jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY): SqlDriver =
    JdbcSqliteDriver(jdbcUrl).also { SegmentDatabase.Schema.create(it) }
