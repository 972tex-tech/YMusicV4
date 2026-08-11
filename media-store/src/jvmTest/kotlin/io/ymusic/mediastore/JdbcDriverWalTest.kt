package io.ymusic.mediastore

import app.cash.sqldelight.db.QueryResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A file-backed driver must come up in WAL with synchronous=NORMAL (the scan write path relies on
 * it not fsync-ing a rollback journal per commit). Like the foreign-keys test, this only holds if
 * the pragmas ride the connection Properties — a fresh connection per statement would otherwise
 * revert to SQLite's default rollback journal.
 */
class JdbcDriverWalTest {

    private fun readPragma(driver: app.cash.sqldelight.db.SqlDriver, pragma: String): String? =
        driver.executeQuery(null, "PRAGMA $pragma", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getString(0))
        }, 0).value

    @Test
    fun file_backed_database_uses_wal_and_normal_sync() {
        val file = File.createTempFile("media-store-wal", ".db").apply { delete() }
        try {
            val driver = mediaStoreJdbcDriver(file.absolutePath)
            try {
                assertEquals("wal", readPragma(driver, "journal_mode")?.lowercase())
                // synchronous NORMAL == 1.
                assertEquals("1", readPragma(driver, "synchronous"))
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
            File(file.absolutePath + "-wal").delete()
            File(file.absolutePath + "-shm").delete()
        }
    }
}
