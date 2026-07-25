package ymusic.ui

import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.DefaultPlatformCache
import kmphttp.KmpHttpClient
import kmphttp.create
import okio.FileSystem
import okio.Path

/** 32 MB: InnerTube bodies are small and mostly revalidated, so this is a warm-start budget. */
const val DefaultHttpCacheBytes: Long = 32L * 1024 * 1024

/**
 * The app's HTTP stack: ONE disk cache under [cacheDir], shared by every client a shell builds.
 *
 * Two clients exist because the engine must carry **no** cookie jar (the scraping JS owns cookie
 * policy) while the player's embed fetch rides the platform cookie store. They differ in nothing
 * else, so [withCookies] derives from [engine] — a second `KmpHttpClient.create {}` would silently
 * drop the cache, and a second [Cache] over the same directory would corrupt it.
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

    fun withCookies(jar: CookieJar): KmpHttpClient = engine.newBuilder().also { it.cookieJar = jar }.build()
}
