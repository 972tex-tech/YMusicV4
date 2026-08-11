package io.ymusic.dynamicengine

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ymusic.dynamicengine.db.CacheDatabase

// sqliter keys in-memory databases by name, so a fixed name would be shared across test methods.
// A per-call unique name gives each test a fresh, isolated in-memory database.
private var dbCounter = 0

internal actual fun testCacheDriver(): SqlDriver {
    dbCounter += 1
    return NativeSqliteDriver(
        schema = CacheDatabase.Schema,
        name = "cache_test_$dbCounter.db",
        onConfiguration = { config -> config.copy(inMemory = true) },
    )
}
