package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of decoded bitmaps, bounded by estimated pixel bytes (w×h×4).
 *
 * Reads are **lock-free** — composition peeks every visible item, so a read must not take even an
 * uncontended lock, and strict LRU (reorder-on-read) would force one. Instead reads hit a volatile
 * immutable [snapshot] and set the entry's `accessed` flag, and eviction is CLOCK/second-chance:
 * the sweep re-appends a recently-read head entry (flag cleared) rather than evicting it — LRU-like
 * quality with zero read-side synchronization. The `accessed` race is benign (a lost set = one
 * entry evicted a round early). Writes serialize on a [Mutex] and republish the snapshot (a
 * reference copy of ≤ a few hundred entries — noise next to the decode that precedes every put).
 */
internal class MemoryImageCache(private val maxBytes: Long) {
    private class Entry(val bitmap: ImageBitmap) {
        var accessed = false
    }

    private val lock = Mutex()
    private val map = LinkedHashMap<String, Entry>() // insertion-ordered; CLOCK hand = the head
    private var totalBytes = 0L

    @Volatile private var snapshot: Map<String, Entry> = emptyMap()

    /** Lock-free lookup; marks the entry recently-used for the eviction sweep. */
    fun peek(key: String): ImageBitmap? = snapshot[key]?.also { it.accessed = true }?.bitmap

    suspend fun put(key: String, bitmap: ImageBitmap): Unit = lock.withLock {
        map.remove(key)?.let { totalBytes -= it.bitmap.byteSize() }
        map[key] = Entry(bitmap)
        totalBytes += bitmap.byteSize()
        while (totalBytes > maxBytes) {
            val eldest = map.entries.firstOrNull() ?: break
            if (eldest.key == key) break // never evict what was just inserted
            val entry = eldest.value
            map.remove(eldest.key)
            if (entry.accessed) {
                // Second chance: recently read — back to the tail, flag spent.
                entry.accessed = false
                map[eldest.key] = entry
            } else {
                totalBytes -= entry.bitmap.byteSize()
            }
        }
        snapshot = HashMap(map)
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * 4
}
