package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import kmpcommon.io.BufferedAsyncSource
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

/**
 * A deliberately simple image loader for Compose: fetch → decode scaled to the view size → cache.
 *
 * - **Memory**: images decode at (or just above) the requested display size — the full-resolution
 *   original is never kept. Decoded bitmaps live in a bounded in-memory LRU keyed by url + size.
 * - **Network cache is [client]'s job**: pass a client built with a `kmphttp.Cache` and every fetch
 *   gets standard HTTP caching for free; the loader adds no second network cache.
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
) {
    private val memory = MemoryImageCache(memoryCacheBytes)
    private val gate = LifoGate(parallelism)

    /**
     * The image at [url], scaled down to fit [maxWidth]×[maxHeight] pixels (aspect kept, never
     * upscaled). Suspends until loaded; throws [IOException] on a failed fetch and rethrows decode
     * failures — callers showing a placeholder catch and log (see `AsyncImage`).
     */
    suspend fun load(url: String, maxWidth: Int, maxHeight: Int): ImageBitmap {
        val w = bucket(maxWidth)
        val h = bucket(maxHeight)
        val key = "$url@${w}x$h"
        memory.get(key)?.let { return it }
        return gate.withPermit {
            memory.get(key)?.let { return@withPermit it } // raced with an identical load
            val bitmap = fromScaledCache(key, w, h) ?: fetchAndDecode(url, key, w, h)
            memory.put(key, bitmap)
            bitmap
        }
    }

    private suspend fun fromScaledCache(key: String, w: Int, h: Int): ImageBitmap? {
        val cache = scaledCache ?: return null
        val bytes = try {
            cache.get(diskKey(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger?.log(Logger.Level.WARN, TAG, "scaled cache read failed: ${e.message}", e)
            null
        } ?: return null
        return try {
            withContext(Dispatchers.Default) { decodeScaled(bytes, w, h) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A corrupt entry falls through to a fresh fetch (which overwrites it).
            logger?.log(Logger.Level.WARN, TAG, "scaled cache entry undecodable: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchAndDecode(url: String, key: String, w: Int, h: Int): ImageBitmap {
        val response = client.execute(RequestBuilder().url(url).build())
        val bytes = try {
            if (!response.isSuccessful) throw IOException("image HTTP ${response.code}: $url")
            BufferedAsyncSource(response.body.asyncSource()).readFully().readByteArray()
        } finally {
            response.close()
        }
        val bitmap = withContext(Dispatchers.Default) { decodeScaled(bytes, w, h) }
        scaledCache?.let { cache ->
            try {
                cache.put(diskKey(key), withContext(Dispatchers.Default) { encodePng(bitmap) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger?.log(Logger.Level.WARN, TAG, "scaled cache write failed: ${e.message}", e)
            }
        }
        return bitmap
    }

    private fun diskKey(key: String): String = key.encodeUtf8().sha256().hex()

    private companion object {
        val TAG = listOf("image-loader")

        /** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
        fun bucket(px: Int): Int = (((px.coerceAtLeast(1) - 1) / 64) + 1) * 64
    }
}
