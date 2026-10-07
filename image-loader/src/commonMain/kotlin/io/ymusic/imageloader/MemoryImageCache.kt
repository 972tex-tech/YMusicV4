package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * In-memory LRU of decoded bitmaps, bounded by estimated pixel bytes (w×h×4). One plain lock
 * around a couple of map operations — cheap enough for [peek]'s composition-time calls because the
 * lock is never held across decode or I/O (put receives an already-decoded bitmap), so the
 * worst-case wait is a few map ops.
 */
internal class MemoryImageCache(initialMaxBytes: Long) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<String, ImageBitmap>()
    private var totalBytes = 0L
    private var maxBytes = initialMaxBytes

    fun peek(key: String): ImageBitmap? = synchronized(lock) {
        map.remove(key)?.also { map[key] = it } // re-insert = most recently used
    }

    /** The largest cached size of [cacheKey] (entries keyed `<cacheKey>@<w>x<h>`), LRU order untouched. */
    fun peekLargest(cacheKey: String): ImageBitmap? = synchronized(lock) {
        var best: ImageBitmap? = null
        for ((key, bitmap) in map) {
            if (key.length <= cacheKey.length + 1 || !key.startsWith(cacheKey) || key[cacheKey.length] != '@') continue
            if (!sizeSuffix.matches(key.substring(cacheKey.length + 1))) continue
            if (best == null || bitmap.byteSize() > best.byteSize()) best = bitmap
        }
        best
    }

    private val sizeSuffix = Regex("\\d+x\\d+")

    fun put(key: String, bitmap: ImageBitmap): Unit = synchronized(lock) {
        map.remove(key)?.let { totalBytes -= it.byteSize() }
        map[key] = bitmap
        totalBytes += bitmap.byteSize()
        // The fresh insert is last in iteration order, so it only evicts itself when it ALONE
        // exceeds the budget — callers hold their own reference for display, so that's just
        // "too big to cache", and a zero budget cleanly disables the cache.
        evictTo(maxBytes)
    }

    /** New steady-state budget; evicts down to it immediately. */
    fun setBudget(bytes: Long): Unit = synchronized(lock) {
        maxBytes = bytes
        evictTo(bytes)
    }

    /** One-shot eviction down to [bytes] (memory pressure); the budget is unchanged. */
    fun trimTo(bytes: Long): Unit = synchronized(lock) {
        evictTo(bytes)
    }

    private fun evictTo(target: Long) {
        val it = map.entries.iterator()
        while (totalBytes > target && it.hasNext()) {
            val eldest = it.next()
            it.remove()
            totalBytes -= eldest.value.byteSize()
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4
}
