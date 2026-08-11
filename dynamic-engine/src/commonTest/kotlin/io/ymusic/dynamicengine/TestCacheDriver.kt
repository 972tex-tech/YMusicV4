package io.ymusic.dynamicengine

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory CacheDatabase driver (schema created) for the cache-store tests. */
internal expect fun testCacheDriver(): SqlDriver
