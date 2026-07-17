package ymusic.imageloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Optional persistent cache of already-scaled images (PNG), so re-showing a thumbnail skips both
 * the network fetch and the full-size decode. Keys are opaque (the loader passes a hash of
 * url + target size). Implementations own their bounds/eviction.
 */
interface ScaledImageCache {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, bytes: ByteArray)
}

/**
 * [ScaledImageCache] on an okio [FileSystem]: one file per key under [directory], bounded to
 * [maxBytes] by deleting oldest-written files first (approximate FIFO — good enough for a
 * regenerable cache; true LRU isn't worth tracking reads on disk).
 */
class DiskScaledImageCache(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val maxBytes: Long = 64L * 1024 * 1024,
) : ScaledImageCache {
    private val writeLock = Mutex()

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = directory / key
        if (!fileSystem.exists(path)) return@withContext null
        fileSystem.read(path) { readByteArray() }
    }

    override suspend fun put(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            fileSystem.createDirectories(directory)
            val tmp = directory / "$key.tmp"
            fileSystem.write(tmp) { write(bytes) }
            fileSystem.atomicMove(tmp, directory / key)
            trim()
        }
    }

    private fun trim() {
        val entries = fileSystem.list(directory).mapNotNull { path ->
            val meta = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
            Triple(path, meta.size ?: 0L, meta.lastModifiedAtMillis ?: 0L)
        }
        var total = entries.sumOf { it.second }
        if (total <= maxBytes) return
        for ((path, size, _) in entries.sortedBy { it.third }) {
            fileSystem.delete(path)
            total -= size
            if (total <= maxBytes) break
        }
    }
}
