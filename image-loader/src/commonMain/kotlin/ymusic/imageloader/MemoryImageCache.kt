package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * In-memory LRU of decoded bitmaps, bounded by estimated pixel bytes (w×h×4). One plain lock
 * around a couple of map operations — cheap enough for [peek]'s composition-time calls because the
 * lock is never held across decode or I/O (put receives an already-decoded bitmap), so the
 * worst-case wait is a few map ops.
 */
internal class MemoryImageCache(private val maxBytes: Long) {
    private val lock = SynchronizedObject()
    private val map = LinkedHashMap<String, ImageBitmap>()
    private var totalBytes = 0L

    fun peek(key: String): ImageBitmap? = synchronized(lock) {
        map.remove(key)?.also { map[key] = it } // re-insert = most recently used
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
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4
}
