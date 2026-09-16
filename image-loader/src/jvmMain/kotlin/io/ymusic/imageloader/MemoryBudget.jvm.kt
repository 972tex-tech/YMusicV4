package io.ymusic.imageloader

/** Desktop memory LRU: decoded thumbnails only. 8MB ≈ a screen of 256px tiles; the disk caches hold the rest. */
fun defaultMemoryCacheBudget(): Long = 8L * 1024 * 1024
