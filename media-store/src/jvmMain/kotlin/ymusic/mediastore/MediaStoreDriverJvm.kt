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
    // Foreign keys must ride the connection Properties (xerial applies them to every connection
    // it opens), NOT a one-shot "PRAGMA foreign_keys=ON" statement: only an in-memory URL keeps a
    // single connection — a file-backed JdbcSqliteDriver opens a fresh connection per
    // non-transactional statement and per transaction, so a pragma executed as a statement
    // configures a throwaway connection and every later one runs with SQLite's default
    // foreign_keys=OFF (no cascades, no reference checks).
    val properties = Properties().apply { setProperty("foreign_keys", "true") }
    return JdbcSqliteDriver(url, properties, MediaStoreDatabase.Schema)
}
