package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory LRU of decoded bitmaps, bounded by estimated pixel bytes (w×h×4). */
internal class MemoryImageCache(private val maxBytes: Long) {
    private val lock = Mutex()
    private val map = LinkedHashMap<String, ImageBitmap>()
    private var totalBytes = 0L

    suspend fun get(key: String): ImageBitmap? = lock.withLock {
        val hit = map.remove(key) ?: return@withLock null
        map[key] = hit // re-insert = most recently used
        hit
    }

    /** Non-suspending [get] for composition-time lookups; a contended lock is simply a miss. */
    fun peek(key: String): ImageBitmap? {
        if (!lock.tryLock()) return null
        try {
            val hit = map.remove(key) ?: return null
            map[key] = hit
            return hit
        } finally {
            lock.unlock()
        }
    }

    suspend fun put(key: String, bitmap: ImageBitmap): Unit = lock.withLock {
        map.remove(key)?.let { totalBytes -= it.byteSize() }
        map[key] = bitmap
        totalBytes += bitmap.byteSize()
        val it = map.entries.iterator()
        while (totalBytes > maxBytes && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue // never evict what was just inserted
            it.remove()
            totalBytes -= eldest.value.byteSize()
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4
}
