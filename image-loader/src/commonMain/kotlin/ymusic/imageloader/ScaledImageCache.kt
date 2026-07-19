package ymusic.imageloader

import kmphttp.cache.DiskLruCache
import kmphttp.cache.OkioFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.use

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
 * [ScaledImageCache] on kmpHttp's journaled [DiskLruCache]: true LRU (a get refreshes the entry),
 * incremental size accounting (no directory scans), crash-safe journaled writes, bounded to
 * [maxBytes].
 */
class DiskScaledImageCache(
    fileSystem: FileSystem,
    directory: Path,
    maxBytes: Long = 64L * 1024 * 1024,
) : ScaledImageCache {
    private val cache = DiskLruCache(
        fileSystem = OkioFileSystem(fileSystem),
        directory = directory,
        valueCount = 1,
        maxSize = maxBytes,
    )

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        cache.get(key)?.use { snapshot ->
            val buffer = Buffer()
            val source = snapshot.getSource(0)
            while (source.read(buffer, SEGMENT) != -1L) {
                // drain to the end
            }
            buffer.readByteArray()
        }
    }

    /** Evict down to the budget now instead of waiting for the throttled background cleanup. */
    suspend fun trimNow() = withContext(Dispatchers.IO) { cache.trimNow() }

    override suspend fun put(key: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val editor = cache.edit(key) ?: return@withContext
        try {
            editor.newSink(0).use { sink ->
                sink.write(Buffer().write(bytes), bytes.size.toLong())
                sink.flush()
            }
            editor.commit()
        } catch (t: Throwable) {
            editor.abort()
            throw t
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
