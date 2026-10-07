package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile

/**
 * A deliberately simple image loader for Compose: resolve an [ImageRequest] via [fetchers] → decode
 * scaled to the view size → cache. Everything is a fetcher — http is [networkFetcher], local files
 * are [fileFetcher], app schemes (embedded album art, content providers) are their own.
 *
 * - **Identity**: every cache layer is keyed by [ImageRequest.key] plus the 64px size bucket (and
 *   the scale), never by a url — the url-only overloads use [imageCacheKey] of the url.
 * - **Memory**: images decode at (or just above) the requested display size — the full-resolution
 *   original is never kept. Decoded bitmaps live in a bounded in-memory LRU.
 * - **[fetchers]** are asked in order per source; the first non-null result wins. Each fetcher
 *   fetches AND decodes — guarding (byte caps, retries) is the fetcher's own. A request with several
 *   sources tries the smallest that covers the size first and falls back across the rest.
 * - **[scaledCache]** (optional) persists the *scaled* image when it is smaller than its source, so
 *   a warm start skips both the fetch and the full-size decode.
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
     * The memory-cached bitmap for [request], or null — non-suspending, cheap enough for
     * composition, so an already-cached image draws on its first frame with no placeholder flash
     * (see `AsyncImage`).
     */
    fun peek(request: ImageRequest): ImageBitmap? = memory.peek(memoryKey(request))

    fun peek(url: String, maxWidth: Int, maxHeight: Int): ImageBitmap? = peek(ImageRequest.of(url, maxWidth, maxHeight))

    /** [load], or null on a failed fetch/decode — logged here, for callers that keep showing what they had. */
    suspend fun loadOrNull(request: ImageRequest): ImageBitmap? =
        try {
            load(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger?.log(Logger.Level.WARN, "load failed: ${request.key}", e)
            null
        }

    suspend fun loadOrNull(url: String, maxWidth: Int, maxHeight: Int): ImageBitmap? =
        loadOrNull(ImageRequest.of(url, maxWidth, maxHeight))

    /**
     * The image for [request], scaled down to cover (or fit) its size, aspect kept, never upscaled.
     * Suspends until loaded; throws [ImageLoadException] (the last source's reason, NotFound only
     * when every source said so) — callers showing a placeholder catch and log (see `AsyncImage`).
     *
     * [cacheInMemory] false keeps the result OUT of the memory LRU (an existing entry is still
     * used) — for callers that hand the bitmap off once at their own size bucket (media3 session
     * artwork) instead of drawing it repeatedly, so the UI's entries aren't evicted for buckets
     * nothing will ever peek.
     */
    suspend fun load(request: ImageRequest, cacheInMemory: Boolean = true): ImageBitmap {
        val key = memoryKey(request)
        memory.peek(key)?.let { return it }
        return gate.withPermit {
            memory.peek(key)?.let { return@withPermit it } // raced with an identical load
            val bitmap = fromScaledCache(key) ?: fetchAndDecode(request, key)
            if (cacheInMemory) memory.put(key, bitmap)
            bitmap
        }
    }

    suspend fun load(url: String, maxWidth: Int, maxHeight: Int, cacheInMemory: Boolean = true): ImageBitmap =
        load(ImageRequest.of(url, maxWidth, maxHeight), cacheInMemory)

    /**
     * The scaled image as a committed FILE in the scaled disk cache, or null when the loader has
     * no [scaledCache]. For consumers that serve bytes across a process boundary (the Android
     * artwork ContentProvider) and never draw: the memory LRU is untouched, and — unlike [load] —
     * the entry is persisted even when the source already fit the request, because the caller
     * needs a file and some schemes (`art://`) have no other on-disk form. The transient decode is
     * dropped after encoding. The path is a live LRU entry that can be evicted later — re-call on a
     * missing file rather than holding the path.
     */
    suspend fun loadFile(request: ImageRequest): okio.Path? {
        val cache = scaledCache ?: return null
        val key = memoryKey(request)
        val disk = diskKey(key)
        cache.filePath(disk)?.let { return it }
        return gate.withPermit {
            cache.filePath(disk)?.let { return@withPermit it } // raced with an identical load
            // A bitmap the UI already decoded at this bucket is persisted as-is; otherwise fetch,
            // bypassing fetchAndDecode so its downscaled-only persist policy doesn't double-write.
            val bitmap = memory.peek(key) ?: fetchFromSources(request).bitmap
            cache.put(disk, bitmap)
            cache.filePath(disk)
                ?: throw ImageLoadException(ImageLoadException.Reason.Undecodable, "scaled entry vanished after write: ${request.key}")
        }
    }

    suspend fun loadFile(url: String, maxWidth: Int, maxHeight: Int): okio.Path? = loadFile(ImageRequest.of(url, maxWidth, maxHeight))

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

    private suspend fun fetchAndDecode(request: ImageRequest, key: String): ImageBitmap {
        val decoded = fetchFromSources(request)
        // Only a genuinely downscaled result is worth persisting: an un-shrunk image re-encoded is
        // often BIGGER than the original its fetcher already holds, and saves no decode.
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

    /** The request's sources in [orderSources] order until one decodes; failures fall through. */
    private suspend fun fetchFromSources(request: ImageRequest): DecodedImage {
        val size = requestBucket(request)
        var failure: ImageLoadException? = null
        for (source in orderSources(request.sources, size, request.scale)) {
            try {
                return fetchSource(source, size, request.scale)
            } catch (e: ImageLoadException) {
                logger?.log(Logger.Level.DEBUG, "source failed (${e.reason}): ${source.url}")
                // Keep the most telling failure: anything beats NotFound, which only wins if unanimous.
                if (failure == null || failure.reason == ImageLoadException.Reason.NotFound) failure = e
            }
        }
        throw failure ?: ImageLoadException(ImageLoadException.Reason.Unsupported, "no sources: ${request.key}")
    }

    private suspend fun fetchSource(source: ImageSource, size: IntSize, scale: ImageScale): DecodedImage {
        val target = Uri.parse(source.url)
        val decoded = fetchers.firstNotNullOfOrNull { it.fetch(target, size, scale) }
            ?: throw ImageLoadException(ImageLoadException.Reason.Unsupported, "no fetcher handles: ${source.url}")
        return decoded.cropped(source.crop)
    }

    /** Bump [SCALED_CACHE_VERSION] when the scaling rule changes: entries scaled the old way must miss. */
    private fun diskKey(key: String): String = "$SCALED_CACHE_VERSION:$key".encodeUtf8().sha256().hex()
}

private const val SCALED_CACHE_VERSION = 3

/** Round the target up to a 64px step: stable cache keys across ±layout-pixel jitter. */
internal fun bucketPx(px: Int): Int = (((px.coerceIn(1, 1 shl 20) - 1) / 64) + 1) * 64

internal fun requestBucket(request: ImageRequest): IntSize =
    IntSize(bucketPx(request.size.width), bucketPx(request.size.height))

/** `key@WxH`, plus `~fit` for a fit decode (smaller than the cover decode of the same box). */
internal fun memoryKey(request: ImageRequest): String {
    val size = requestBucket(request)
    val suffix = if (request.scale == ImageScale.Fit) "~fit" else ""
    return "${request.key}@${size.width}x${size.height}$suffix"
}

/**
 * Sources to try for a [size] box: those whose declared size (after crop) covers it, smallest
 * first; then those with no declared size, in order; then the smaller ones, largest first — the
 * best available when nothing covers. A single source is just itself.
 */
internal fun orderSources(sources: List<ImageSource>, size: IntSize, scale: ImageScale): List<ImageSource> {
    if (sources.size < 2) return sources
    val (known, unknown) = sources.partition { it.width > 0 && it.height > 0 }
    val (covering, smaller) = known.partition { covers(it, size, scale) }
    return covering.sortedBy { effectiveWidth(it) } + unknown + smaller.sortedByDescending { effectiveWidth(it) }
}

internal fun effectiveWidth(source: ImageSource): Int = source.width

internal fun effectiveHeight(source: ImageSource): Int = when (source.crop) {
    SourceCrop.None -> source.height
    SourceCrop.Letterbox16x9 -> minOf(source.height, source.width * 9 / 16)
}

/** The source (after crop) is large enough that decoding it for [size] needs no upscale. */
internal fun covers(source: ImageSource, size: IntSize, scale: ImageScale): Boolean {
    val w = effectiveWidth(source)
    val h = effectiveHeight(source)
    if (w <= 0 || h <= 0) return false
    return when (scale) {
        ImageScale.Cover -> w >= size.width && h >= size.height
        ImageScale.Fit -> w >= size.width || h >= size.height
    }
}
