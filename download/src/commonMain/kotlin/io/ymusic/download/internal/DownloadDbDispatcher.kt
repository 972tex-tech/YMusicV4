package io.ymusic.download.internal

import kotlinx.coroutines.CoroutineDispatcher

/** The dispatcher all download-DB work runs on. JVM/Android use IO; native has no IO dispatcher so it
 *  falls back to Default. Mirrors :media-store's StoreDispatcher. */
internal expect fun downloadDbDispatcher(): CoroutineDispatcher
