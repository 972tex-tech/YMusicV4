package ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ymusic.mediastore.db.MediaStoreDatabase
import java.util.Properties

/**
 * A JVM (JDBC) driver with the schema created and foreign keys enabled.
 *
 * @param path filesystem path to the database file, or ":memory:" for an in-memory database.
 */
fun mediaStoreJdbcDriver(path: String): SqlDriver {
    val url = if (path == ":memory:") JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path"
    val driver = JdbcSqliteDriver(url, Properties(), MediaStoreDatabase.Schema)
    // JdbcSqliteDriver holds a single connection, so this pragma persists for the DB's lifetime.
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return driver
}
