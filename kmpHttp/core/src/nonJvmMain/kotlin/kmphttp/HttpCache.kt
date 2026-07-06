package kmphttp

import kmpcommon.io.AsyncSink

/**
 * Caches HTTP and HTTPS responses so they may be reused, saving time and
 * bandwidth.
 *
 * Sometimes a request will result in a conditional cache hit. If the cache contains a stale copy of
 * the response, the client will issue a conditional `GET`. The server will then send either
 * the updated response if it has changed, or a short 'not modified' response if the client's copy
 * is still valid. Such responses increment both the network count and hit count.
 *
 * The best way to improve the cache hit rate is by configuring the web server to return cacheable
 * responses. Although this client honors all [HTTP/1.1 (RFC 7234)][rfc_7234] cache headers, it
 * doesn't cache partial responses.
 *
 * ## Force a Network Response
 *
 * In some situations, such as after a user clicks a 'refresh' button, it may be necessary to skip
 * the cache, and fetch data directly from the server. To force a full refresh, add the `no-cache`
 * directive:
 *
 * ```kotlin
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder().noCache().build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build()
 * ```
 *
 * If it is only necessary to force a cached response to be validated by the server, use the more
 * efficient `max-age=0` directive instead:
 *
 * ```kotlin
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxAge(0, TimeUnit.SECONDS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build()
 * ```
 *
 * ## Force a Cache Response
 *
 * Sometimes you'll want to show resources if they are available immediately, but not otherwise.
 * This can be used so your application can show *something* while waiting for the latest data to be
 * downloaded. To restrict a request to locally-cached resources, add the `only-if-cached`
 * directive:
 *
 * ```kotlin
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .onlyIfCached()
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build()
 * Response forceCacheResponse = client.newCall(request).execute();
 * if (forceCacheResponse.code() != 504) {
 *   // The resource was cached! Show it.
 * } else {
 *   // The resource was not cached.
 * }
 * ```
 *
 * This technique works even better in situations where a stale response is better than no response.
 * To permit stale cached responses, use the `max-stale` directive with the maximum staleness in
 * seconds:
 *
 * ```java
 * Request request = new Request.Builder()
 *     .cacheControl(new CacheControl.Builder()
 *         .maxStale(365, TimeUnit.DAYS)
 *         .build())
 *     .url("http://publicobject.com/helloworld.txt")
 *     .build()
 * ```
 *
 * The [CacheControl] class can configure request caching directives and parse response caching
 * directives. It even offers convenient constants [CacheControl.FORCE_NETWORK] and
 * [CacheControl.FORCE_CACHE] that address the use cases above.
 *
 * [rfc_7234]: http://tools.ietf.org/html/rfc7234
 *
 * This is the internal cache SPI consumed by [kmphttp.cache.CacheInterceptor]. The public,
 * cross-platform handle is the [Cache] expect type (on non-JVM it aliases [kmphttp.cache.DefaultCache]).
 */
interface HttpCache {
    val currentSize: Long
    val initialized: Boolean
    val closed: Boolean
    suspend fun initialize()
    suspend fun flush()
    suspend fun close()

    suspend fun get(request: Request): Response?
    suspend fun put(response: Response): CacheRequest?
    suspend fun remove(request: Request)
    suspend fun update(
        cached: Response,
        network: Response,
    )

    interface CacheRequest {
        fun bodyWriter(): AsyncSink
        fun abort()
    }
}
