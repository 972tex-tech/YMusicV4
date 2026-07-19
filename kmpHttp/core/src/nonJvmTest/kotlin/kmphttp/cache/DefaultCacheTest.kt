package kmphttp.cache

import kmpcommon.io.AsyncSource
import kmphttp.MediaType
import kmphttp.Protocol
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.internal.stripBody
import kmphttp.runTestSkipBrowser
import kmphttp.toHttpUrl
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for DefaultCache implementation.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
class DefaultCacheTest {

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

    private fun createCache(dir: Path, maxSize: Long = 10 * 1024 * 1024): DefaultCache {
        return DefaultCache(dir, maxSize, fileSystem)
    }

    // region Basic Operations

    @Test
    fun testEmptyCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            assertTrue(cache.initialized)
            assertEquals(0L, cache.currentSize)
            assertFalse(cache.closed)

            val request = RequestBuilder()
                .url("https://example.com/test")
                .build()

            assertNull(cache.get(request))
            cache.close()
            assertTrue(cache.closed)
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testPutAndGetResponse() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val request = RequestBuilder()
                .url("https://example.com/test")
                .build()

            val responseBody = "Hello, World!".toTestResponseBody()
            val now = currentTimeMillis()
            val networkResponse = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/plain")
                .header("Content-Length", "13")
                .header("Cache-Control", "max-age=3600")
                .body(responseBody)
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            // Create response with networkResponse for varyHeaders
            val response = networkResponse.newBuilder()
                .networkResponse(networkResponse.stripBodyForTest())
                .build()

            val cacheRequest = cache.put(response)
            assertNotNull(cacheRequest)

            // Write body to cache
            val writer = cacheRequest.bodyWriter()
            val bodyBuffer = Buffer().apply { writeUtf8("Hello, World!") }
            writer.write(bodyBuffer, bodyBuffer.size)
            writer.close()

            // Retrieve from cache
            val cachedResponse = cache.get(request)
            assertNotNull(cachedResponse)
            assertEquals(200, cachedResponse.code)
            assertEquals("OK", cachedResponse.message)
            assertEquals(Protocol.HTTP_1_1, cachedResponse.protocol)

            cachedResponse.body.close()
            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testRemoveFromCache() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val request = RequestBuilder()
                .url("https://example.com/remove-test")
                .build()

            val response = createCacheableResponse(request)
            val cacheRequest = cache.put(response)
            assertNotNull(cacheRequest)

            // Write body and close
            val writer = cacheRequest.bodyWriter()
            val bodyBuffer = Buffer().apply { writeUtf8("test") }
            writer.write(bodyBuffer, bodyBuffer.size)
            writer.close()

            kotlinx.coroutines.delay(100)

            // Verify cached
            assertNotNull(cache.get(request)?.also { it.body.close() })

            // Remove
            cache.remove(request)

            // Verify removed
            assertNull(cache.get(request))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Non-cacheable Responses

    @Test
    fun testNonGetRequestNotCached() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            // POST request should not be cached
            val request = RequestBuilder()
                .url("https://example.com/post-test")
                .post(kmphttp.RequestBody.EMPTY)
                .build()

            val now = currentTimeMillis()
            val response = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "max-age=3600")
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val networkResponse = response.newBuilder()
                .networkResponse(response.stripBodyForTest())
                .build()

            val cacheRequest = cache.put(networkResponse)
            assertNull(cacheRequest) // POST should not be cached

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    @Test
    fun testVaryAllNotCached() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            val request = RequestBuilder()
                .url("https://example.com/vary-all")
                .build()

            val now = currentTimeMillis()
            // Response with Vary: * should not be cached
            val response = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "max-age=3600")
                .header("Vary", "*")
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val networkResponse = response.newBuilder()
                .networkResponse(response.stripBodyForTest())
                .build()

            val cacheRequest = cache.put(networkResponse)
            assertNull(cacheRequest) // Vary: * should not be cached

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Cache Key

    @Test
    fun testCacheKeyIsMd5OfUrl() {
        val url1 = "https://example.com/test".toHttpUrl()
        val url2 = "https://example.com/other".toHttpUrl()
        val url1Again = "https://example.com/test".toHttpUrl()

        val key1 = DefaultCache.key(url1)
        val key2 = DefaultCache.key(url2)
        val key1Again = DefaultCache.key(url1Again)

        // Same URL should produce same key
        assertEquals(key1, key1Again)

        // Different URLs should produce different keys
        assertFalse(key1 == key2)

        // Key should be valid (lowercase hex, 32 chars for MD5)
        assertTrue(key1.matches(Regex("[a-f0-9]{32}")))
    }

    // endregion

    // region Closed Cache

    @Test
    fun testClosedCacheReturnsNull() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()
            cache.close()

            assertTrue(cache.closed)

            val request = RequestBuilder()
                .url("https://example.com/test")
                .build()

            // Operations on closed cache should return null
            assertNull(cache.get(request))
            assertNull(cache.put(createCacheableResponse(request)))

            // Remove should not throw on closed cache
            cache.remove(request)
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Vary Headers

    @Test
    fun testVaryHeaderMatching() = runTestSkipBrowser {
        val cacheDir = newCacheDir()
        try {
            val cache = createCache(cacheDir)
            cache.initialize()

            // Create request with Accept-Language header
            val request = RequestBuilder()
                .url("https://example.com/vary-test")
                .header("Accept-Language", "en-US")
                .build()

            val now = currentTimeMillis()
            // Create response that varies on Accept-Language
            val response = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "max-age=3600")
                .header("Vary", "Accept-Language")
                .body("en content".toTestResponseBody())
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            // Network response needs the request headers for vary matching
            val networkResponse = ResponseBuilder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Cache-Control", "max-age=3600")
                .header("Vary", "Accept-Language")
                .sentRequestAtMillis(now)
                .receivedResponseAtMillis(now)
                .build()

            val fullResponse = response.newBuilder()
                .networkResponse(networkResponse.stripBodyForTest())
                .build()

            val cacheRequest = cache.put(fullResponse)
            assertNotNull(cacheRequest)

            val writer = cacheRequest.bodyWriter()
            val bodyBuffer = Buffer().apply { writeUtf8("en content") }
            writer.write(bodyBuffer, bodyBuffer.size)
            writer.close()

            kotlinx.coroutines.delay(100)

            // Same Accept-Language should match
            val sameRequest = RequestBuilder()
                .url("https://example.com/vary-test")
                .header("Accept-Language", "en-US")
                .build()
            assertNotNull(cache.get(sameRequest)?.also { it.body.close() })

            // Different Accept-Language should not match
            val differentRequest = RequestBuilder()
                .url("https://example.com/vary-test")
                .header("Accept-Language", "fr-FR")
                .build()
            assertNull(cache.get(differentRequest))

            cache.close()
        } finally {
            cleanupDir(cacheDir)
        }
    }

    // endregion

    // region Helpers

    private fun createCacheableResponse(request: kmphttp.Request): Response {
        val now = currentTimeMillis()
        val response = ResponseBuilder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Cache-Control", "max-age=3600")
            .body("test".toTestResponseBody())
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()

        return response.newBuilder()
            .networkResponse(response.stripBodyForTest())
            .build()
    }

    private fun String.toTestResponseBody(): ResponseBody {
        val content = this
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = content.length.toLong()
            override fun asyncSource(): AsyncSource = StringAsyncSource(content)
        }
    }

    private fun Response.stripBodyForTest(): Response {
        return stripBody()
    }

    private class StringAsyncSource(content: String) : AsyncSource {
        private val buffer = Buffer().apply { writeUtf8(content) }
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

    // endregion
}
