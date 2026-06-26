package kmphttp

import kmphttp.cache.DefaultCache
import okio.Path

/**
 * On non-JVM platforms the public [Cache] handle is the disk LRU [DefaultCache], which implements
 * the internal [HttpCache] SPI consumed by the cache interceptor.
 */
actual typealias Cache = DefaultCache

actual fun DefaultPlatformCache(
    folderName: Path,
    maxSize: Long
): Cache = DefaultCache(folderName, maxSize)
