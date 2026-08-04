package ymusic.download

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import ymusic.download.db.DownloadDatabase

/** Native (sqliter) [SqlDriver] for the download queue, placed in the platform default DB dir by [name]. */
fun downloadDatabaseNativeDriver(name: String = "downloads.db"): SqlDriver =
    NativeSqliteDriver(schema = DownloadDatabase.Schema, name = name)
