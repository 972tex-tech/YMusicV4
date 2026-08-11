package io.ymusic.ytmedia

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory SQLite driver with the SegmentDatabase schema created (JVM = JDBC, native = sqliter). */
internal expect fun testSegmentDriver(): SqlDriver
