package io.ymusic.ytmedia

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ymusic.ytmedia.db.SegmentDatabase

// sqliter keys in-memory databases by name, so a fixed name would be shared across test methods.
// A per-call unique name gives each test its own fresh in-memory database.
private var dbCounter = 0

internal actual fun testSegmentDriver(): SqlDriver {
    dbCounter += 1
    return NativeSqliteDriver(
        schema = SegmentDatabase.Schema,
        name = "segment_test_$dbCounter.db",
        onConfiguration = { config -> config.copy(inMemory = true) },
    )
}
