package io.ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// One dedicated daemon thread for all DB access. JdbcSqliteDriver holds its JDBC connection in a
// ThreadLocal, so confining every store operation to a single thread guarantees one consistent
// connection (critical for an in-memory DB, where a second connection is a *different* database) and
// serial access (SQLite is single-writer anyway). Daemon so it never blocks JVM shutdown.
private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "media-store-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

internal actual fun storeDispatcher(): CoroutineDispatcher = dbDispatcher
