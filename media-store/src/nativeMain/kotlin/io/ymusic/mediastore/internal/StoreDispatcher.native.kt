package io.ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

// One dedicated thread for all DB access, matching JVM/Android — every store operation runs on a
// single connection to a single database instance. (sqliter is thread-safe, but confining to one
// thread keeps the model uniform and serial, which SQLite writers require anyway.)
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val dbDispatcher: CoroutineDispatcher = newSingleThreadContext("media-store-db")

internal actual fun storeDispatcher(): CoroutineDispatcher = dbDispatcher
