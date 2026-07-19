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
import kotlin.concurrent.Volatile

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
            val bitmap = fromScaledCache(key) ?: fetchAndDecode(url, key, w, h)
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

    private suspend fun fetchAndDecode(url: String, key: String, w: Int, h: Int): ImageBitmap {
        val response = client.execute(RequestBuilder().url(url).build())
        val bytes = try {
            if (!response.isSuccessful) throw IOException("image HTTP ${response.code}: $url")
            BufferedAsyncSource(response.body.asyncSource()).readFully().readByteArray()
        } finally {
            response.close()
        }
        val decoded = withContext(Dispatchers.Default) { decodeScaled(bytes, w, h) }
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded as
        // PNG is often BIGGER than the original the HTTP cache already holds, and saves no decode.
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

    private fun diskKey(key: String): String = key.encodeUtf8().sha256().hex()

    private companion object {
        val TAG = listOf("image-loader")
    }
}

/** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
internal fun bucketPx(px: Int): Int = (((px.coerceIn(1, 1 shl 20) - 1) / 64) + 1) * 64
