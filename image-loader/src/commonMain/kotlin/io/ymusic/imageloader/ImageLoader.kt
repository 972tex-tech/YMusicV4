package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.encodeUtf8
import okio.IOException
import kotlin.concurrent.Volatile

/**
 * A deliberately simple image loader for Compose: resolve via [fetchers] → decode scaled to the
 * view size → cache. Everything is a fetcher — http is [networkFetcher], local files are
 * [fileFetcher], app schemes (embedded album art, content providers) are their own.
 *
 * - **Memory**: images decode at (or just above) the requested display size — the full-resolution
 *   original is never kept. Decoded bitmaps live in a bounded in-memory LRU keyed by url + size.
 * - **[fetchers]** are asked in order; the first non-null result wins. Each fetcher fetches AND
 *   decodes to cover the requested size — guarding (byte caps, retries) is the fetcher's own.
 * - **[scaledCache]** (optional) persists the *scaled* image, so a warm start skips both the fetch
 *   and the full-size decode.
 * - **Priority is LIFO** with [parallelism] concurrent loads: the newest request wins, and a
 *   caller's cancellation (e.g. a list item scrolled away, its composition gone) frees its slot
 *   immediately — see [LifoGate].
 */
class ImageLoader(
    private val fetchers: List<ImageFetcher>,
    private val scaledCache: ScaledImageCache? = null,
    logger: Logger? = null,
    memoryCacheBytes: Long = 32L * 1024 * 1024,
    parallelism: Int = 4,
) {
    internal val logger = logger?.fork("image-loader")
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

    /** [load], or null on a failed fetch/decode — logged here, for callers that keep showing what they had. */
    suspend fun loadOrNull(url: String, maxWidth: Int, maxHeight: Int, cacheKey: String = url): ImageBitmap? =
        try {
            load(url, maxWidth, maxHeight, cacheKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger?.log(Logger.Level.WARN, "load failed: $url", e)
            null
        }

    /** Any size of [cacheKey] already in memory (the largest) — an interim frame while [load] runs. */
    fun peekAnySize(cacheKey: String): ImageBitmap? = memory.peekLargest(cacheKey)

    /**
     * The image at [url], scaled down to cover [maxWidth]×[maxHeight] pixels (aspect kept, never
     * upscaled). Suspends until loaded; throws [IOException] on a failed fetch and rethrows decode
     * failures — callers showing a placeholder catch and log (see `AsyncImage`).
     *
     * [cacheKey] keys the memory + scaled caches instead of the URL — pass a stable identity when
     * the URL carries volatile parts (rotating signature params, per-session CDN hosts), so a new
     * URL for the same image still hits the caches. The network fetch always uses the real [url].
     *
     * [cacheInMemory] false keeps the result OUT of the memory LRU (an existing entry is still
     * used) — for callers that hand the bitmap off once at their own size bucket (media3 session
     * artwork) instead of drawing it repeatedly, so the UI's entries aren't evicted for buckets
     * nothing will ever peek.
     */
    suspend fun load(
        url: String,
        maxWidth: Int,
        maxHeight: Int,
        cacheKey: String = url,
        cacheInMemory: Boolean = true,
    ): ImageBitmap {
        val w = bucketPx(maxWidth)
        val h = bucketPx(maxHeight)
        val key = "$cacheKey@${w}x$h"
        memory.peek(key)?.let { return it }
        return gate.withPermit {
            memory.peek(key)?.let { return@withPermit it } // raced with an identical load
            val bitmap = fromScaledCache(key) ?: fetchAndDecode(url, key, w, h)
            if (cacheInMemory) memory.put(key, bitmap)
            bitmap
        }
    }

    /**
     * The scaled image as a committed FILE in the scaled disk cache, or null when the loader has
     * no [scaledCache]. For consumers that serve bytes across a process boundary (the Android
     * artwork ContentProvider) and never draw: the memory LRU is untouched, and — unlike [load] —
     * the entry is persisted even when the source already fit the request (`downscaled` or not),
     * because the caller needs a file and some schemes (`art://`) have no other on-disk form. The
     * transient decode is dropped after encoding. The path is a live LRU entry that can be evicted
     * later — re-call on a missing file rather than holding the path.
     */
    suspend fun loadFile(url: String, maxWidth: Int, maxHeight: Int, cacheKey: String = url): okio.Path? {
        val cache = scaledCache ?: return null
        val w = bucketPx(maxWidth)
        val h = bucketPx(maxHeight)
        val key = "$cacheKey@${w}x$h"
        val disk = diskKey(key)
        cache.filePath(disk)?.let { return it }
        return gate.withPermit {
            cache.filePath(disk)?.let { return@withPermit it } // raced with an identical load
            // A bitmap the UI already decoded at this bucket is persisted as-is; otherwise fetch,
            // bypassing fetchAndDecode so its downscaled-only persist policy doesn't double-write.
            val bitmap = memory.peek(key) ?: run {
                val target = Uri.parse(url)
                fetchers.firstNotNullOfOrNull { it.fetch(target, IntSize(w, h)) }?.bitmap
                    ?: throw IOException("no fetcher handles: $url")
            }
            cache.put(disk, bitmap)
            cache.filePath(disk) ?: throw IOException("scaled entry vanished after write: $url")
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
            logger?.log(Logger.Level.WARN, "scaled cache read failed: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchAndDecode(url: String, key: String, w: Int, h: Int): ImageBitmap {
        val size = IntSize(w, h)
        // Parse the caller's url once; fetchers dispatch on the uri's scheme.
        val target = Uri.parse(url)
        val decoded = fetchers.firstNotNullOfOrNull { it.fetch(target, size) }
            ?: throw IOException("no fetcher handles: $url")
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded as
        // PNG is often BIGGER than the original its fetcher already holds, and saves no decode.
        if (decoded.downscaled) {
            scaledCache?.let { cache ->
                try {
                    cache.put(diskKey(key), decoded.bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger?.log(Logger.Level.WARN, "scaled cache write failed: ${e.message}", e)
                }
            }
        }
        return decoded.bitmap
    }

    /** Bump [SCALED_CACHE_VERSION] when the scaling rule changes: entries scaled the old way must miss. */
    private fun diskKey(key: String): String = "$SCALED_CACHE_VERSION:$key".encodeUtf8().sha256().hex()
}

private const val SCALED_CACHE_VERSION = 2

/** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
internal fun bucketPx(px: Int): Int = (((px.coerceIn(1, 1 shl 20) - 1) / 64) + 1) * 64
