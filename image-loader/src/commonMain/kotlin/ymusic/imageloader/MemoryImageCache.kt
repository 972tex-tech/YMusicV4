package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * In-memory LRU of decoded bitmaps, bounded by estimated pixel bytes (w×h×4).
 *
 * A cache **miss** is lock-free — composition peeks every visible item until its bitmap lands, so
 * the miss path must not take even an uncontended lock: it's one volatile read of the immutable
 * [snapshot] plus a hash lookup. A **hit** takes the lock to bump the entry to most-recently-used —
 * hits are rare per item (`AsyncImage` stops peeking once it holds the bitmap), so exact LRU is
 * worth a plain critical section there. Recency order lives only in [map]; the snapshot is
 * lookup-only, so a bump doesn't republish it — only [put] mutations do.
 */
internal class MemoryImageCache(private val maxBytes: Long) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<String, ImageBitmap>()
    private var totalBytes = 0L

    @Volatile private var snapshot: Map<String, ImageBitmap> = emptyMap()

    fun peek(key: String): ImageBitmap? {
        val hit = snapshot[key] ?: return null
        synchronized(lock) {
            map.remove(key)?.let { map[key] = it } // re-insert = most recently used
        }
        return hit
    }

    fun put(key: String, bitmap: ImageBitmap): Unit = synchronized(lock) {
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
        snapshot = HashMap(map)
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4
}
