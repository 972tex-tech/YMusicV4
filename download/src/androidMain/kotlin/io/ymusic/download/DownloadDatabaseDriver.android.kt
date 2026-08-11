package io.ymusic.download

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ymusic.download.db.DownloadDatabase

/** Android [SqlDriver] for the download queue (create-if-new, migrate-if-older). Null [name] = in-memory. */
fun downloadDatabaseAndroidDriver(context: Context, name: String? = "downloads.db"): SqlDriver =
    AndroidSqliteDriver(schema = DownloadDatabase.Schema, context = context, name = name)
