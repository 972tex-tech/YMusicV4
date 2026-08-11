package io.ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// One dedicated daemon thread for all DB access (see the JVM actual for the rationale — AndroidSqliteDriver
// is thread-safe, but a single confinement thread keeps access serial and consistent regardless).
private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "media-store-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

internal actual fun storeDispatcher(): CoroutineDispatcher = dbDispatcher
