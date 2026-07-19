package kmphttp.cache

import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmphttp.AsyncInterceptor
import kmphttp.CacheControl
import kmphttp.HttpStatus.HTTP_GATEWAY_TIMEOUT
import kmphttp.HttpStatus.HTTP_NOT_MODIFIED
import kmphttp.HttpStatus.HTTP_OK
import kmphttp.MediaType
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.runTestSkipBrowser
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for CacheInterceptor implementation.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
class CacheInterceptorTest {

    private val fileSystem = okio.FileSystem.SYSTEM
    private val testBaseDir = ".".toPath() / "test-cache"

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun newCacheDir(): Path {
        val dir = testBaseDir / Uuid.random().toString()
        fileSystem.createDirectories(dir)
        return dir
    }

    private fun cleanupDir(dir: Path) {
        // Cleanup disabled for debugging
    }

    private fun createCache(dir: Path): DefaultCache {
        return DefaultCache(dir, 10 * 1024 * 1024, fileSystem)
    }

    // region Cache Miss

    @Test
    fun testCacheMissGoesToNetwork() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            val request = RequestBuilder()
                .url("https://example.com/cache-miss")
                .build()

            val networkResponse = createNetworkResponse(request, "network response")
            val chain = FakeChain(request, networkResponse)

            val response = interceptor.intercept(chain)

            assertEquals(HTTP_OK, response.code)
            assertNotNull(response.networkResponse)
            assertNull(response.cacheResponse)

            response.body.close()
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Only-if-cached

    @Test
    fun testOnlyIfCachedWithNoCacheReturns504() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            // Request with only-if-cached using CacheControl.FORCE_CACHE
            val request = RequestBuilder()
                .url("https://example.com/only-if-cached")
                .cacheControl(CacheControl.FORCE_CACHE)
                .build()

            val networkResponse = createNetworkResponse(request, "should not be used")
            val chain = FakeChain(request, networkResponse)

            val response = interceptor.intercept(chain)

            // Should return 504 Gateway Timeout
            assertEquals(HTTP_GATEWAY_TIMEOUT, response.code)
            assertEquals("Unsatisfiable Request (only-if-cached)", response.message)

            response.body.close()
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Cache Hit

    @Test
    fun testCacheHitReturnsFromCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            val request = RequestBuilder()
                .url("https://example.com/cache-hit")
                .build()

            // First request - populate cache
            val networkResponse = createCacheableNetworkResponse(request, "cached content")
            val chain1 = FakeChain(request, networkResponse)
            val response1 = interceptor.intercept(chain1)
            // Read body to trigger caching
            response1.body.asyncSource().let { source ->
                BufferedAsyncSource(source).use { it.readFully() }
            }
            response1.body.close()

            // Second request - should hit cache
            val chain2 = FakeChain(request, createNetworkResponse(request, "new network"))
            val response2 = interceptor.intercept(chain2)

            // Should be from cache (no network call)
            assertNotNull(response2.cacheResponse)
            assertNull(response2.networkResponse)

            response2.body.close()
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Conditional Request (304 Not Modified)

    @Test
    fun testConditionalRequestWith304() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            val request = RequestBuilder()
                .url("https://example.com/conditional")
                .build()

            val now = currentTimeMillis()
            // First request - populate cache with ETag
            val networkResponse = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .header("Cache-Control", "max-age=0") // Force revalidation
                .header("ETag", "\"abc123\"")
                .body("original content".toTestResponseBody())
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val chain1 = FakeChain(request, networkResponse)
            val response1 = interceptor.intercept(chain1)
            // Read body to trigger caching
            response1.body.asyncSource().let { source ->
                val buffer = Buffer()
                while (source.read(buffer, 8192) != -1L) { }
            }
            response1.body.close()

            // Second request - should get 304
            val now2 = currentTimeMillis()
            val notModifiedResponse = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_NOT_MODIFIED)
                .message("Not Modified")
                .header("ETag", "\"abc123\"")
                .sentRequestAtMillis(now2)
                .receivedResponseAtMillis(now2)
                .build()

            val chain2 = FakeChain(request, notModifiedResponse)
            val response2 = interceptor.intercept(chain2)

            // Should combine cache and network
            assertNotNull(response2.cacheResponse)
            assertNotNull(response2.networkResponse)
            assertEquals(HTTP_OK, response2.code) // Uses cache body

            response2.body.close()
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region No Cache Interceptor

    @Test
    fun testNullCacheGoesDirectlyToNetwork() = runTestSkipBrowser {
        val interceptor = CacheInterceptor(null)

        val request = RequestBuilder()
            .url("https://example.com/no-cache")
            .build()

        val networkResponse = createNetworkResponse(request, "direct response")
        val chain = FakeChain(request, networkResponse)

        val response = interceptor.intercept(chain)

        assertEquals(HTTP_OK, response.code)
        assertNull(response.cacheResponse)

        response.body.close()
    }

    // endregion

    // region No-Store Directive

    @Test
    fun testNoStoreResponseNotCached() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            val request = RequestBuilder()
                .url("https://example.com/no-store")
                .build()

            val now = currentTimeMillis()
            // Response with no-store should not be cached
            val networkResponse = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .header("Cache-Control", "no-store")
                .body("sensitive data".toTestResponseBody())
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val chain = FakeChain(request, networkResponse)
            val response = interceptor.intercept(chain)
            response.body.close()

            // Should not be in cache
            assertNull(cache.get(request))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region POST Invalidates Cache

    @Test
    fun testPostInvalidatesCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val interceptor = CacheInterceptor(cache)

            val getRequest = RequestBuilder()
                .url("https://example.com/resource")
                .build()

            // First populate cache with GET
            val getResponse = createCacheableNetworkResponse(getRequest, "cached")
            val chain1 = FakeChain(getRequest, getResponse)
            val response1 = interceptor.intercept(chain1)
            response1.body.asyncSource().let { source ->
                val buffer = Buffer()
                while (source.read(buffer, 8192) != -1L) { }
            }
            response1.body.close()

            // Verify cached
            assertNotNull(cache.get(getRequest)?.also { it.body.close() })

            // POST to same URL should invalidate cache
            val postRequest = RequestBuilder()
                .url("https://example.com/resource")
                .post(kmphttp.RequestBody.EMPTY)
                .build()

            val now = currentTimeMillis()
            val postResponse = ResponseBuilder()
                .request(postRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val chain2 = FakeChain(postRequest, postResponse)
            val response2 = interceptor.intercept(chain2)
            response2.body.close()

            // Cache should be invalidated
            assertNull(cache.get(getRequest))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Helpers

    private fun createNetworkResponse(request: Request, body: String): Response {
        val now = currentTimeMillis()
        val response = ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .body(body.toTestResponseBody())
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()

        return response
    }

    private fun createCacheableNetworkResponse(request: Request, body: String): Response {
        val bodyByteArray = body.encodeToByteArray()
        val now = currentTimeMillis()
        val response = ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .header("Cache-Control", "max-age=3600")
            .header("Content-Length", bodyByteArray.size.toString())
            .body(bodyByteArray.toTestResponseBody())
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()

        return response
    }

    private fun ByteArray.toTestResponseBody(): ResponseBody {
        val content = this
        val source = ByteArrayAsyncSource(content)
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = content.size.toLong()
            override fun asyncSource(): AsyncSource = source
        }
    }

    private fun String.toTestResponseBody(): ResponseBody {
        return this.encodeToByteArray().toTestResponseBody()
    }

    private class ByteArrayAsyncSource(content: ByteArray) : AsyncSource {
        private val buffer = Buffer().apply { write(content) }
        private var closed = false

        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (closed) return -1
            if (buffer.size == 0L) return -1
            return buffer.read(sink, minOf(byteCount, buffer.size))
        }

        override fun close() {
            closed = true
            buffer.clear()
        }
    }

    private class FakeChain(
        override val request: Request,
        private val networkResponse: Response
    ) : AsyncInterceptor.Chain {
        override val options = RedirectOptions(followRedirects = true, followSslRedirects = true)

        override suspend fun proceed(request: Request): Response {
            // Return network response with the given request attached
            return networkResponse.newBuilder()
                .request(request)
                .build()
        }
    }

    // endregion
}
