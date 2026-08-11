package io.ymusic.ytmedia

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.ytmedia.db.SegmentDatabase
import java.util.Properties

internal actual fun testSegmentDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), SegmentDatabase.Schema)
