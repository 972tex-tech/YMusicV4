package kmphttp

import okio.Path

/**
 * Opaque persistent lru-cache implementation for [KmpHttpClient].
 */
expect class Cache {
    fun size(): Long
    fun maxSize(): Long
}

/**
 * Create a default cache for the current platform
 * (except Browser, no-ops cache implement returns instead).
 */
expect fun DefaultPlatformCache(
    folderName: Path,
    maxSize: Long
): Cache
