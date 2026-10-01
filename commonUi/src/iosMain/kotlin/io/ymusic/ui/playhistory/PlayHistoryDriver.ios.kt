package io.ymusic.ui.playhistory

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/** Native (sqliter) [SqlDriver] for watch-time ranking, in the platform default DB dir. */
fun playHistoryNativeDriver(name: String = "play-history.db"): SqlDriver =
    NativeSqliteDriver(schema = PlayHistorySchema, name = name)
