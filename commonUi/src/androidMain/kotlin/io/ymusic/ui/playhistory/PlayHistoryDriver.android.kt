package io.ymusic.ui.playhistory

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Android [SqlDriver] for watch-time ranking. Null [name] = in-memory. */
fun playHistoryAndroidDriver(context: Context, name: String? = "play-history.db"): SqlDriver =
    AndroidSqliteDriver(schema = PlayHistorySchema, context = context, name = name)
