package io.ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.ytmedia.db.SegmentDatabase
import java.util.Properties

/**
 * Build the JVM (JDBC) [SqlDriver] for [SqlDelightSegmentIndex]. Pass a `jdbc:sqlite:/path/segments.db`
 * URL for a persistent index, or the default in-memory URL for tests / throwaway runs. Keeps the
 * sqldelight-jdbc dependency inside `:yt-media` — callers only see [SqlDriver].
 *
 * Handing the [SegmentDatabase.Schema] to the driver makes creation **idempotent** (create-if-new,
 * migrate-if-older, no-op if current) — so a file-backed index survives across launches without the
 * "table already exists" a one-shot `Schema.create` throws on the second run.
 */
fun jvmSegmentDatabaseDriver(jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY): SqlDriver =
    JdbcSqliteDriver(jdbcUrl, Properties(), SegmentDatabase.Schema)
