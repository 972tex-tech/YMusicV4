package kmphttp

import kmphttp.RequestBodyX.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.use
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Smoke-test suite for the platform-default [KmpHttpClient] (OkHttp on JVM, NSURLSession on Apple,
 * libcurl on Linux) created via [KmpHttpClient.create]. Cache tests use a platform disk cache from
 * [newTestCache]. Hits the local [TestServer].
 */
class HttpClientSmokeTests {

    // Builds a one-off client for tests that need a specific config (custom interceptors, a cache).
    private fun newClient(
        configure: KmpHttpClient.BaseBuilder<*>.() -> Unit = {}
    ): KmpHttpClient = KmpHttpClient.create {
        defaultExecuteTimeout = 30.seconds
        // Trust the local test server's self-signed leaf (anchors-only); see [testServerSslConfig].
        sslConfig = testServerSslConfig()
        configure()
    }

    // Reused across the plain tests instead of building a client per request.
    private val client by lazy { newClient() }

    @Test
    fun testSimpleGet() = runTest {
        val request = RequestBuilder().url(TestServer.url("/get")).get().build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            assertNotNull(response.body)
        }
    }

    @Test
    fun testGetWithQueryParams() = runTest {
        val request = RequestBuilder().url(TestServer.url("/get?foo=bar&baz=qux")).get().build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            val bodyString = response.body.utf8String()
            assertTrue(bodyString.contains("\"foo\""))
            assertTrue(bodyString.contains("\"bar\""))
        }
    }

    @Test
    fun testGetWithHeaders() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/headers"))
            .header("X-Custom-Header", "CustomValue")
            .header("X-Another-Header", "AnotherValue")
            .get()
            .build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            val bodyString = response.body.utf8String()
            assertTrue(bodyString.contains("X-Custom-Header"))
            assertTrue(bodyString.contains("CustomValue"))
            assertTrue(bodyString.contains("AnotherValue"))
        }
    }

    @Test
    fun testPost() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/post"))
            .post("Hello, World!".toRequestBody(null))
            .build()
        client.execute(request).use { response ->
            val bodyString = response.body.utf8String()
            assertEquals(200, response.code, bodyString)
            assertTrue(bodyString.contains("Hello, World!"))
        }
    }

    @Test
    fun testPut() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/put"))
            .put("""{"key":"value"}""".toRequestBody(null))
            .build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.utf8String().contains("value"))
        }
    }

    @Test
    fun testDelete() = runTest {
        val request = RequestBuilder().url(TestServer.url("/delete")).deleteWithoutBody().build()
        client.execute(request).use { response -> assertEquals(200, response.code) }
    }

    @Test
    fun testPatch() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/patch"))
            .patch("""{"patched":true}""".toRequestBody(null))
            .build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.utf8String().contains("patched"))
        }
    }

    @Test
    fun testStatusCodes() = runTest {
        client.execute(RequestBuilder().url(TestServer.url("/status/404")).get().build()).use {
            assertEquals(404, it.code)
        }
        client.execute(RequestBuilder().url(TestServer.url("/status/500")).get().build()).use {
            assertEquals(500, it.code)
        }
    }

    @Test
    fun testExpect100Continue() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/post"))
            .post("test body".toRequestBody(null))
            .header("Expect", "100-continue")
            .build()
        client.execute(request).use { response ->
            val bodyString = response.body.utf8String()
            assertEquals(200, response.code, bodyString)
            assertTrue(bodyString.contains("test body"))
        }
    }

    @Test
    fun testResponseHeaders() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/response-headers?X-Test-Header=TestValue&Access-Control-Expose-Headers=X-Test-Header"))
            .get()
            .build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            assertEquals("TestValue", response.header("X-Test-Header"))
        }
    }

    @Test
    fun testInterceptor() = runTest {
        var called = false
        val newClient = newClient {
            interceptors.add { chain ->
                called = true
                chain.proceed(chain.request.newBuilder().addHeader("X-Interceptor", "Added").build())
            }
        }
        val request = RequestBuilder().url(TestServer.url("/headers")).get().build()
        newClient.execute(request).use { response ->
            assertEquals(200, response.code)
            assertTrue(called)
            assertContains(response.body.utf8String(), "X-Interceptor")
        }
    }

    @Test
    fun testMultipleInterceptors() = runTest {
        val callOrder = mutableListOf<Int>()
        val newClient = newClient {
            interceptors.add { chain ->
                callOrder.add(1)
                val response = chain.proceed(chain.request)
                callOrder.add(4)
                response
            }
            interceptors.add { chain ->
                callOrder.add(2)
                val response = chain.proceed(chain.request)
                callOrder.add(3)
                response
            }
        }
        val request = RequestBuilder().url(TestServer.url("/get")).get().build()
        newClient.execute(request).use { response ->
            assertEquals(200, response.code)
            assertEquals(listOf(1, 2, 3, 4), callOrder)
        }
    }

    @Test
    fun testNetworkInterceptor() = runTest {
        var called = false
        val newClient = newClient {
            networkInterceptors.add { chain ->
                called = true
                chain.proceed(chain.request.newBuilder().addHeader("X-Net-Interceptor", "net").build())
            }
        }
        val request = RequestBuilder().url(TestServer.url("/headers")).get().build()
        newClient.execute(request).use { response ->
            assertEquals(200, response.code)
            assertTrue(called, "network interceptor should run inside the engine chain")
            assertContains(response.body.utf8String(), "X-Net-Interceptor")
        }
    }

    @Test
    fun testNewBuilderRoundTrips() = runTest {
        val derived = client.newBuilder().build()
        val request = RequestBuilder().url(TestServer.url("/get")).get().build()
        derived.execute(request).use { response -> assertEquals(200, response.code) }
    }

    @Test
    fun testUserAgent() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/headers"))
            .header("User-Agent", "KmpHttpTest/1.0")
            .get()
            .build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            assertContains(response.body.utf8String(), "KmpHttpTest/1.0")
        }
    }

    @Test
    fun testFollowRedirects() = runTest {
        val request = RequestBuilder().url(TestServer.url("/redirect/3")).get().build()
        val options = RedirectOptions(followRedirects = true, followSslRedirects = true)
        client.execute(request, options).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.request.url.toString().endsWith("/anything"))
        }
    }

    @Test
    fun testDisableFollowRedirects() = runTest {
        val request = RequestBuilder().url(TestServer.url("/redirect/1")).get().build()
        val options = RedirectOptions(followRedirects = false, followSslRedirects = false)
        client.execute(request, options).use { response ->
            assertEquals(302, response.code)
            assertNotNull(response.header("Location"))
        }
    }

    @Test
    fun testRedirectToAbsoluteUrl() = runTest {
        val request = RequestBuilder().url(TestServer.url("/absolute-redirect/2")).get().build()
        val options = RedirectOptions(followRedirects = true, followSslRedirects = true)
        client.execute(request, options).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.request.url.toString().endsWith("/anything"))
        }
    }

    @Test
    fun testPriorResponseChain() = runTest {
        val request = RequestBuilder().url(TestServer.url("/redirect/2")).get().build()
        client.execute(request).use { response ->
            assertEquals(200, response.code)
            val prior1 = response.priorResponse
            assertNotNull(prior1)
            assertEquals(302, prior1.code)
            val prior2 = prior1.priorResponse
            assertNotNull(prior2)
            assertEquals(302, prior2.code)
            assertEquals(null, prior2.priorResponse)
        }
    }

    @Test
    fun testExecuteTimeout() = runTest {
        val request = RequestBuilder().url(TestServer.url("/delay/3")).get().build()
        assertFailsWith<HttpTimeoutException> {
            client.execute(request, timeout = 500.milliseconds)
        }
    }

    @Test
    fun testExecuteWithinTimeout() = runTest {
        val request = RequestBuilder().url(TestServer.url("/delay/1")).get().build()
        client.execute(request, timeout = 5.seconds).use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun testTimeoutWhileReadingResponseBody() = runTest {
        val request = RequestBuilder()
            .url(TestServer.url("/drip?duration=5&numbytes=10&code=200&delay=0"))
            .get()
            .build()
        client.execute(request, timeout = 30.seconds).use { response ->
            assertEquals(200, response.code)
            assertFailsWith<TimeoutCancellationException> {
                withContext(Dispatchers.Default) {
                    withTimeout(500.milliseconds) { response.body.utf8String() }
                }
            }
        }
    }

    // region Cache Tests

    @Test
    fun testCacheHit() = runTest {
        val cached = newClient { cache = newTestCache() }
        val request = RequestBuilder().url(TestServer.url("/cache/60")).get().build()

        cached.execute(request).use { response1 ->
            assertEquals(200, response1.code)
            assertNotNull(response1.networkResponse, "First request should have networkResponse")
            assertNull(response1.cacheResponse, "First request should not have cacheResponse")
            response1.body.utf8String()
        }
        cached.execute(request).use { response2 ->
            assertEquals(200, response2.code)
            assertNull(response2.networkResponse, "Second request should be a cache hit")
            assertNotNull(response2.cacheResponse)
        }
    }

    @Test
    fun testCacheMissForNonCacheableResponse() = runTest {
        val cached = newClient { cache = newTestCache() }
        val request = RequestBuilder()
            .url(TestServer.url("/response-headers?Cache-Control=no-store"))
            .get()
            .build()
        cached.execute(request).use { it.body.utf8String() }
        cached.execute(request).use { response2 ->
            assertEquals(200, response2.code)
            assertNotNull(response2.networkResponse, "no-store response should not be cached")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun testForceCacheReturns504WhenNotCached() = runTest {
        val cached = newClient { cache = newTestCache() }
        val request = RequestBuilder()
            .url(TestServer.url("/get?unique=${Uuid.random()}"))
            .cacheControl(CacheControl.FORCE_CACHE)
            .get()
            .build()
        cached.execute(request).use { response ->
            assertEquals(504, response.code, "FORCE_CACHE with no cache should return 504")
            assertEquals("Unsatisfiable Request (only-if-cached)", response.message)
        }
    }

    @Test
    fun testForceNetworkBypassesCache() = runTest {
        val cached = newClient { cache = newTestCache() }
        val url = TestServer.url("/cache/60")
        cached.execute(RequestBuilder().url(url).get().build()).use { it.body.utf8String() }
        val forceNetwork = RequestBuilder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get().build()
        cached.execute(forceNetwork).use { response2 ->
            assertEquals(200, response2.code)
            assertNotNull(response2.networkResponse, "FORCE_NETWORK should hit network")
        }
    }

    @Test
    fun testNoCacheDirectiveForcesRevalidation() = runTest {
        val cached = newClient { cache = newTestCache() }
        val url = TestServer.url("/cache/60")
        cached.execute(RequestBuilder().url(url).get().build()).use { it.body.utf8String() }
        val noCache = RequestBuilder().url(url).header("Cache-Control", "no-cache").get().build()
        cached.execute(noCache).use { response2 ->
            assertEquals(200, response2.code)
            assertNotNull(response2.networkResponse, "no-cache should force network request")
        }
    }

    @Test
    fun testPrivateCacheControlIsCacheable() = runTest {
        val cached = newClient { cache = newTestCache() }
        val request = RequestBuilder()
            .url(TestServer.url("/response-headers?Cache-Control=private,max-age=60"))
            .get()
            .build()
        cached.execute(request).use { it.body.utf8String() }
        cached.execute(request).use { response2 ->
            assertEquals(200, response2.code)
            assertNull(response2.networkResponse, "private cache-control should be cacheable")
            assertNotNull(response2.cacheResponse)
        }
    }

    @Test
    fun testMaxAgeZeroForcesNetwork() = runTest {
        val cached = newClient { cache = newTestCache() }
        val request = RequestBuilder()
            .url(TestServer.url("/response-headers?Cache-Control=max-age=0"))
            .get()
            .build()
        cached.execute(request).use { it.body.utf8String() }
        cached.execute(request).use { response2 ->
            assertEquals(200, response2.code)
            assertNotNull(response2.networkResponse, "max-age=0 should force network")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun testCacheWithVaryHeader() = runTest {
        val cached = newClient { cache = newTestCache() }
        val uniqueId = Uuid.random().toString()
        val url = TestServer.url("/response-headers?Cache-Control=max-age=60&Vary=Accept-Language&X-Unique=$uniqueId")

        val request1 = RequestBuilder().url(url).header("Accept-Language", "en").get().build()
        cached.execute(request1).use { it.body.utf8String() }
        cached.execute(request1).use { response2 ->
            assertEquals(200, response2.code)
            assertNull(response2.networkResponse, "Same Vary header should hit cache")
        }
        val request3 = RequestBuilder().url(url).header("Accept-Language", "fr").get().build()
        cached.execute(request3).use { response3 ->
            assertEquals(200, response3.code)
            assertNotNull(response3.networkResponse, "Different Vary header should miss cache")
        }
    }

    // endregion
}
