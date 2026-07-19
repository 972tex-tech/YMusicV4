package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kmpcommon.io.AsyncSource
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile

/**
 * A deliberately simple image loader for Compose: fetch → decode scaled to the view size → cache.
 *
 * - **Memory**: images decode at (or just above) the requested display size — the full-resolution
 *   original is never kept. Decoded bitmaps live in a bounded in-memory LRU keyed by url + size.
 * - **[sourceCache]** (recommended) owns the downloaded originals on disk, Coil/Glide-style — the
 *   body streams to disk and decodes from the file; the [client] should then carry NO http cache.
 *   Without it, fetches buffer in memory (bounded by [maxImageBytes]) and network caching is the
 *   client's `kmphttp.Cache`.
 * - **[scaledCache]** (optional) persists the *scaled* image, so a warm start skips both the fetch
 *   and the full-size decode.
 * - **Priority is LIFO** with [parallelism] concurrent loads: the newest request wins, and a
 *   caller's cancellation (e.g. a list item scrolled away, its composition gone) frees its slot
 *   immediately — see [LifoGate].
 */
class ImageLoader(
    private val client: KmpHttpClient,
    private val scaledCache: ScaledImageCache? = null,
    internal val logger: Logger? = null,
    memoryCacheBytes: Long = 32L * 1024 * 1024,
    parallelism: Int = 4,
    /** Hard cap on a fetched body — a mislinked huge file must fail, not OOM the process. */
    private val maxImageBytes: Long = 32L * 1024 * 1024,
    /**
     * Compressed originals on disk (the Coil/Glide model): the network body streams to disk and
     * decoding happens from the file — the compressed image never sits in the heap whole, and all
     * size buckets share one download. Give the [client] NO http cache when this is set (double
     * storage otherwise). Null = the body is buffered in memory (bounded by [maxImageBytes]).
     */
    private val sourceCache: ImageSourceCache? = null,
    /** Custom url resolvers tried first (e.g. `art://` → ffmpeg-extracted embedded album art). */
    private val fetchers: List<ImageFetcher> = emptyList(),
) {
    private val memory = MemoryImageCache(memoryCacheBytes)
    private val gate = LifoGate(parallelism)

    @Volatile private var memoryBudget = memoryCacheBytes

    /** New steady-state memory-cache budget (device class changed, user setting); evicts to it now. */
    fun setMemoryCacheBudget(bytes: Long) {
        memoryBudget = bytes
        memory.setBudget(bytes)
    }

    /**
     * Memory pressure (Android `onTrimMemory`, backgrounding): evict now down to [keepFraction] of
     * the budget — 0 clears — without changing the steady-state budget, so the cache regrows
     * normally once pressure passes.
     */
    fun trimMemoryCache(keepFraction: Float = 0f) {
        memory.trimTo((memoryBudget * keepFraction.coerceIn(0f, 1f)).toLong())
    }

    /**
     * The memory-cached bitmap for [url] at this target size, or null — non-suspending, cheap
     * enough for composition, so an already-cached image draws on its first frame with no
     * placeholder flash (see `AsyncImage`).
     */
    fun peek(url: String, maxWidth: Int, maxHeight: Int, cacheKey: String = url): ImageBitmap? =
        memory.peek("$cacheKey@${bucketPx(maxWidth)}x${bucketPx(maxHeight)}")

    /**
     * The image at [url], scaled down to fit [maxWidth]×[maxHeight] pixels (aspect kept, never
     * upscaled). Suspends until loaded; throws [IOException] on a failed fetch and rethrows decode
     * failures — callers showing a placeholder catch and log (see `AsyncImage`).
     *
     * [cacheKey] keys the memory + scaled caches instead of the URL — pass a stable identity when
     * the URL carries volatile parts (rotating signature params, per-session CDN hosts), so a new
     * URL for the same image still hits the caches. The network fetch always uses the real [url].
     */
    suspend fun load(url: String, maxWidth: Int, maxHeight: Int, cacheKey: String = url): ImageBitmap {
        val w = bucketPx(maxWidth)
        val h = bucketPx(maxHeight)
        val key = "$cacheKey@${w}x$h"
        memory.peek(key)?.let { return it }
        return gate.withPermit {
            memory.peek(key)?.let { return@withPermit it } // raced with an identical load
            val bitmap = fromScaledCache(key) ?: fetchAndDecode(url, cacheKey, key, w, h)
            memory.put(key, bitmap)
            bitmap
        }
    }

    private suspend fun fromScaledCache(key: String): ImageBitmap? {
        val cache = scaledCache ?: return null
        return try {
            cache.get(diskKey(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A corrupt or unreadable entry falls through to a fresh fetch (which overwrites it).
            logger?.log(Logger.Level.WARN, TAG, "scaled cache read failed: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchAndDecode(url: String, cacheKey: String, key: String, w: Int, h: Int): ImageBitmap {
        val decoded = fetchCustom(url, w, h)
            ?: localFilePath(url)?.let { path ->
                withContext(Dispatchers.Default) { decodeScaledFile(systemFileSystem, path, w, h) }
            }
            ?: sourceCache?.let { fetchViaSourceCache(it, url, cacheKey, w, h) }
            ?: fetchInMemory(url, w, h)
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded as
        // PNG is often BIGGER than the original the source/HTTP cache already holds, and saves no
        // decode.
        if (decoded.downscaled) {
            scaledCache?.let { cache ->
                try {
                    cache.put(diskKey(key), decoded.bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger?.log(Logger.Level.WARN, TAG, "scaled cache write failed: ${e.message}", e)
                }
            }
        }
        return decoded.bitmap
    }

    /** Coil/Glide model: original on disk (keyed by URL identity, size-independent) → decode from
     *  the file. A VANISHED file (evicted underneath us) refetches; a file that decodes as garbage
     *  fails as-is — refetching would download the same bytes on every load until LRU evicts it. */
    private suspend fun fetchViaSourceCache(
        src: ImageSourceCache,
        url: String,
        cacheKey: String,
        w: Int,
        h: Int,
    ): DecodedImage {
        val srcKey = diskKey(cacheKey)
        src.filePath(srcKey)?.let { file ->
            try {
                return withContext(Dispatchers.Default) { decodeScaledFile(src.fileSystem, file, w, h) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (src.fileSystem.exists(file)) throw e
                logger?.log(Logger.Level.WARN, TAG, "source cache entry vanished, refetching: ${e.message}", e)
            }
        }
        val response = client.execute(RequestBuilder().url(url).build())
        val file = try {
            if (!response.isSuccessful) throw IOException("image HTTP ${response.code}: $url")
            val declared = response.body.contentLength()
            if (declared > maxImageBytes) {
                throw IOException("image body $declared bytes exceeds the $maxImageBytes-byte cap: $url")
            }
            src.write(srcKey, response.body.asyncSource(), maxImageBytes, url)
        } finally {
            response.close()
        }
        return withContext(Dispatchers.Default) { decodeScaledFile(src.fileSystem, file, w, h) }
    }

    /** No source cache: the body is buffered in memory, bounded by the cap + magic sniff. */
    private suspend fun fetchInMemory(url: String, w: Int, h: Int): DecodedImage {
        val response = client.execute(RequestBuilder().url(url).build())
        val bytes = try {
            if (!response.isSuccessful) throw IOException("image HTTP ${response.code}: $url")
            val declared = response.body.contentLength()
            if (declared > maxImageBytes) {
                throw IOException("image body $declared bytes exceeds the $maxImageBytes-byte cap: $url")
            }
            readImageBytes(response.body.asyncSource(), url)
        } finally {
            response.close()
        }
        return withContext(Dispatchers.Default) { decodeScaled(bytes, w, h) }
    }

    /** Stream the body in with the size cap re-checked as bytes arrive (Content-Length can lie or
     *  be absent). Whether the bytes ARE an image is the decoder's verdict. */
    private suspend fun readImageBytes(source: AsyncSource, url: String): ByteArray {
        val buffer = Buffer()
        while (true) {
            val n = source.read(buffer, SEGMENT)
            if (buffer.size > maxImageBytes) {
                throw IOException("image exceeds the $maxImageBytes-byte cap: $url")
            }
            if (n == -1L) break
        }
        return buffer.readByteArray()
    }

    private suspend fun fetchCustom(url: String, w: Int, h: Int): DecodedImage? {
        for (fetcher in fetchers) {
            val bytes = fetcher.fetch(url) ?: continue
            return withContext(Dispatchers.Default) { decodeScaled(bytes, w, h) }
        }
        return null
    }

    /** `file://` urls decode straight from disk (no network, no source cache); the path is taken
     *  verbatim after the scheme — pass unencoded paths. */
    private fun localFilePath(url: String): okio.Path? =
        if (url.startsWith("file://")) url.removePrefix("file://").toPath() else null

    private fun diskKey(key: String): String = key.encodeUtf8().sha256().hex()

    private companion object {
        val TAG = listOf("image-loader")
        const val SEGMENT = 64L * 1024
    }
}

/** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
internal fun bucketPx(px: Int): Int = (((px.coerceIn(1, 1 shl 20) - 1) / 64) + 1) * 64
