package io.ymusic.ui.sponsorblock

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/** JVM (JDBC) [SqlDriver] for SponsorBlock. Pass a filesystem path, or ":memory:" for tests. */
fun sponsorBlockJdbcDriver(path: String): SqlDriver {
    val inMemory = path == ":memory:"
    val url = if (inMemory) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path"
    val properties = Properties().apply {
        if (!inMemory) {
            setProperty("journal_mode", "WAL")
            setProperty("synchronous", "NORMAL")
            setProperty("busy_timeout", "5000")
        }
    }
    return JdbcSqliteDriver(url, properties, SponsorBlockSchema)
}
