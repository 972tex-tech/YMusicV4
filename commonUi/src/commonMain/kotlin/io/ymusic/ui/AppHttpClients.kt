package io.ymusic.ui

import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.DefaultPlatformCache
import kmphttp.HttpLogInterceptor
import kmphttp.KmpHttpClient
import kmphttp.create
import okio.FileSystem
import okio.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 32 MB: InnerTube bodies are small and mostly revalidated, so this is a warm-start budget. */
const val DefaultHttpCacheBytes: Long = 32L * 1024 * 1024

/**
 * The app's HTTP stack: ONE disk cache under [cacheDir], and ONE client on it — the [engine]
 * client, which carries **no** cookie jar (the scraping JS owns cookie policy). A second
 * `KmpHttpClient.create {}` would silently drop the cache, and a second [Cache] over the same
 * directory would corrupt it — derive any variant from [engine] via `newBuilder()`.
 *
 * Thumbnails stay out of this: `:image-loader` streams originals into its own disk cache and decodes
 * from the file, so an HTTP cache in front of it would store every image twice.
 */
class AppHttpClients(
    cacheDir: Path,
    maxSizeBytes: Long = DefaultHttpCacheBytes,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    logger: kmpcommon.log.Logger? = null,
) {

    private val httpLogger = logger?.fork("kmphttp")
    private val diskCache: Cache = run {
        fileSystem.createDirectories(cacheDir)
        DefaultPlatformCache(cacheDir, maxSizeBytes)
    }

    private val base: KmpHttpClient = KmpHttpClient.create {
        defaultExecuteTimeout = APP_HTTP_TIMEOUT
        cache = diskCache
        this.logger = httpLogger // `logger` alone resolves to the constructor parameter
    }

    val engine: KmpHttpClient = base.newBuilder().apply {
        httpLogger?.let { interceptors += HttpLogInterceptor(it) }
    }.build()

    /**
     * The image loader's client — derived from the same base as [engine] so the whole app shares
     * one connection pool, minus the HTTP cache (the loader streams originals into its own disk
     * cache; see class doc) and the per-request log (thumbnail traffic would drown it). One YouTube quirk lives here: `i.ytimg.com/img/no_thumbnail.jpg` —
     * the default thumbnail YouTube emits for playlists with nothing to thumbnail — is a real
     * placeholder JPEG served with status 404. The failing status would keep it out of every image
     * cache, so each rebind refetched it; rewriting that one response to 200 lets it decode and
     * cache like any other image.
     */
    val image: KmpHttpClient = base.newBuilder().apply {
        cache = null
        networkInterceptors += AsyncInterceptor { chain ->
            val response = chain.proceed(chain.request)
            if (response.code == 404 && chain.request.url.toString().contains("/img/no_thumbnail")) {
                response.newBuilder().code(200).message("OK").build()
            } else {
                response
            }
        }
    }.build()
}

/** The app's network timeout: no response headers within this fails as a network error
 *  (HttpTimeoutException). Every client the app creates sets it; the feed watchdog outlasts it. */
val APP_HTTP_TIMEOUT: Duration = 10.seconds
