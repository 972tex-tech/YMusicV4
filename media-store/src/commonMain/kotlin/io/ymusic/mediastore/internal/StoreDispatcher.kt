package io.ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher the store runs its (blocking) SQLite work on: [kotlinx.coroutines.Dispatchers.IO]
 * on JVM/Android, [kotlinx.coroutines.Dispatchers.Default] on native (which has no IO dispatcher).
 */
internal expect fun storeDispatcher(): CoroutineDispatcher
