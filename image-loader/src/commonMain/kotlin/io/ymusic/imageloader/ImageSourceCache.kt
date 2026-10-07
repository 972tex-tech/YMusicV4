package io.ymusic.imageloader

import kmpcommon.io.AsyncSource
import kmphttp.cache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

/**
 * Compressed originals, owned by the loader (the Coil/Glide model): the network body streams to
 * disk — the heap holds one segment, never the image — and decoding happens from the FILE. Keyed by
 * URL identity only (no size), so every size bucket shares one download. The image client should
 * carry no HTTP cache of its own — this replaces it, with LRU-by-size instead of HTTP freshness
 * (right for immutable-per-URL thumbnails).
 */
internal class ImageSourceCache(
    internal val fileSystem: FileSystem,
    directory: Path,
    maxBytes: Long = 128L * 1024 * 1024,
) {
    private val cache = DiskLruCache(
        fileSystem = fileSystem,
        directory = directory,
        valueCount = 1,
        maxSize = maxBytes,
    )

    /** The committed entry's file (LRU-refreshed), or null on a miss. */
    internal suspend fun filePath(key: String): Path? = withContext(Dispatchers.IO) {
        cache.get(key)?.use { it.getPath(0) }
    }

    /**
     * Streams [body] into the entry and returns the committed file. The byte cap is enforced while
     * streaming (Content-Length can lie or be absent); whether the bytes ARE an image is the
     * decoder's verdict, not ours. Nothing is committed on failure.
     */
    internal suspend fun write(
        key: String,
        body: AsyncSource,
        maxImageBytes: Long,
        url: String,
    ): Path = withContext(Dispatchers.IO) {
        val editor = cache.edit(key) ?: throw IOException("image source cache uneditable: $key")
        try {
            editor.newSink(0).use { sink ->
                val buffer = Buffer()
                var total = 0L
                while (true) {
                    val n = body.read(buffer, SEGMENT)
                    if (n == -1L) break
                    total += n
                    if (total > maxImageBytes) {
                        throw ImageLoadException(
                            ImageLoadException.Reason.Undecodable,
                            "image exceeds the $maxImageBytes-byte cap: $url",
                        )
                    }
                    sink.write(buffer, buffer.size)
                }
                sink.flush()
            }
            editor.commit()
        } catch (t: Throwable) {
            editor.abort()
            throw t
        }
        filePath(key) ?: throw IOException("image source entry vanished after commit: $key")
    }

    /** Evict down to the budget now instead of waiting for the throttled background cleanup. */
    suspend fun trimNow() = withContext(Dispatchers.IO) { cache.trimNow() }

    /** Flush the journal and release the cache; the directory stays valid for a new instance. */
    suspend fun close() = withContext(Dispatchers.IO) { cache.close() }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
