package kmphttp.internal

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Default dispatcher for IO-bound operations.
 * - On JVM/Native: Uses Dispatchers.IO
 * - On JS: Uses Dispatchers.Default (JS is single-threaded)
 */
internal expect val DefaultIODispatcher: CoroutineDispatcher
