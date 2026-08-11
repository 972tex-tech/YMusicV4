package io.ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ymusic.ytmedia.db.SegmentDatabase

/**
 * Build the native (sqliter) [SqlDriver] for [SqlDelightSegmentIndex] with the schema created
 * (create-if-new, migrate-if-older — [NativeSqliteDriver] handles it). [name] is the on-disk database
 * file (in the platform's default database directory); a persistent index for the Apple player cache.
 * Keeps the sqldelight-native dependency inside `:yt-media` — callers only see [SqlDriver].
 */
fun nativeSegmentDatabaseDriver(name: String = "yt_segments.db"): SqlDriver =
    NativeSqliteDriver(schema = SegmentDatabase.Schema, name = name)
