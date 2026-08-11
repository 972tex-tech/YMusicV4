package io.ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.mediastore.db.MediaStoreDatabase
import java.util.Properties

/**
 * A JVM (JDBC) driver with the schema created and foreign keys enabled.
 *
 * @param path filesystem path to the database file, or ":memory:" for an in-memory database.
 */
fun mediaStoreJdbcDriver(path: String): SqlDriver {
    val inMemory = path == ":memory:"
    val url = if (inMemory) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path"
    // Pragmas must ride the connection Properties (xerial applies them to every connection it opens),
    // NOT a one-shot "PRAGMA …" statement: only an in-memory URL keeps a single connection — a
    // file-backed JdbcSqliteDriver opens a fresh connection per non-transactional statement and per
    // transaction, so a pragma executed as a statement configures a throwaway connection and every
    // later one runs with SQLite's defaults (foreign_keys=OFF, rollback journal).
    val properties = Properties().apply {
        setProperty("foreign_keys", "true")
        if (!inMemory) {
            // WAL + synchronous=NORMAL: a commit appends to the write-ahead log instead of fsync-ing
            // a rollback journal every transaction, so a scan's thousands of tiny writes don't
            // saturate the store's single DB thread (and reads see the last checkpoint rather than
            // queueing behind the writer). Safe: at most the last transaction is lost on power loss,
            // never corruption. WAL is a no-op on :memory:, so it's file-only for clarity.
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("busy_timeout", "5000")
        }
    }
    return JdbcSqliteDriver(url, properties, MediaStoreDatabase.Schema)
}
