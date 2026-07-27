package ymusic.ui

import kmphttp.Cache
import kmphttp.DefaultPlatformCache
import kmphttp.KmpHttpClient
import kmphttp.create
import okio.FileSystem
import okio.Path

/** 32 MB: InnerTube bodies are small and mostly revalidated, so this is a warm-start budget. */
const val DefaultHttpCacheBytes: Long = 32L * 1024 * 1024

/**
 * The app's HTTP stack: ONE disk cache under [cacheDir], and ONE client on it — the [engine]
 * client, which carries **no** cookie jar (the scraping JS owns cookie policy; even the player's
 * embed page now comes through the engine's `yt/embed`). A second `KmpHttpClient.create {}` would
 * silently drop the cache, and a second [Cache] over the same directory would corrupt it — derive
 * any variant from [engine] via `newBuilder()`.
 *
 * Thumbnails stay out of this: `:image-loader` streams originals into its own disk cache and decodes
 * from the file, so an HTTP cache in front of it would store every image twice.
 */
class AppHttpClients(
    cacheDir: Path,
    maxSizeBytes: Long = DefaultHttpCacheBytes,
    fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val diskCache: Cache = run {
        fileSystem.createDirectories(cacheDir)
        DefaultPlatformCache(cacheDir, maxSizeBytes)
    }

    val engine: KmpHttpClient = KmpHttpClient.create { cache = diskCache }
}
