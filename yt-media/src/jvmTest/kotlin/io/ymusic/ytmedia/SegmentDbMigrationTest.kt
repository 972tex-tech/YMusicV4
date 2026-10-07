package io.ymusic.ytmedia

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.db.SegmentDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** A v1 cache DB (no `format_init`) must migrate to v2 in place — not error with "no such table". */
class SegmentDbMigrationTest {

    @Test
    fun v1_to_v2_adds_format_init() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Recreate the v1 schema: the tables that existed before format_init landed.
        driver.execute(
            null,
            "CREATE TABLE blob_span (key TEXT NOT NULL, start INTEGER NOT NULL, len INTEGER NOT NULL, " +
                "last_used INTEGER NOT NULL, PRIMARY KEY (key, start, len))",
            0,
        )
        driver.execute(
            null,
            "CREATE TABLE segment (itag INTEGER NOT NULL, lmt INTEGER NOT NULL, seg_index INTEGER NOT NULL, " +
                "xtags TEXT, vid TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, " +
                "r_start INTEGER NOT NULL, r_end INTEGER NOT NULL, download_time INTEGER NOT NULL, " +
                "PRIMARY KEY (itag, lmt, seg_index))",
            0,
        )

        assertTrue(SegmentDatabase.Schema.version >= 2L, "adding format_init must bump the schema version")
        SegmentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = SegmentDatabase.Schema.version)

        // format_init now exists and round-trips through the index.
        val index = SqlDelightSegmentIndex(driver, now = { 0 })
        index.putFormatInit(251, 100, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), index.formatInit(251, 100))
    }
}
