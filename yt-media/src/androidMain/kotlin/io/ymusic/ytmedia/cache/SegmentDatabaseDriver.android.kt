package io.ymusic.ytmedia.cache

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ymusic.ytmedia.db.SegmentDatabase

/**
 * Build the Android [SqlDriver] for [SqlDelightSegmentIndex] with the schema created (create-if-new,
 * migrate-if-older — [AndroidSqliteDriver] handles it). Pass a file [name] for a persistent index, or
 * null for in-memory (tests). Keeps the sqldelight-android dependency inside `:yt-media` — callers only
 * see [SqlDriver].
 */
fun androidSegmentDatabaseDriver(context: Context, name: String? = "yt_segments.db"): SqlDriver =
    AndroidSqliteDriver(schema = SegmentDatabase.Schema, context = context, name = name)
