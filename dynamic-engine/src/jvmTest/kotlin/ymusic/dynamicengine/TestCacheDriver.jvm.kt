package ymusic.dynamicengine

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ymusic.dynamicengine.db.CacheDatabase
import java.util.Properties

internal actual fun testCacheDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CacheDatabase.Schema)
