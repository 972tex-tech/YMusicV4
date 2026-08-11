package io.ymusic.imageloader

/** Desktop default for [ImageLoader]'s memory cache: an eighth of the JVM heap, clamped sane. */
fun defaultMemoryCacheBudget(): Long =
    (Runtime.getRuntime().maxMemory() / 8).coerceIn(32L * 1024 * 1024, 256L * 1024 * 1024)
