package io.ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediastore.mediaStoreNativeDriver

// sqliter keys in-memory databases by name, so give each test its own name for isolation.
private var counter = 0

internal actual fun newTestDriver(): SqlDriver {
    counter += 1
    return mediaStoreNativeDriver(name = "scanner_test_$counter.db", inMemory = true)
}
