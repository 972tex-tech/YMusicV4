package kmphttp

import kotlinx.coroutines.test.runTest
import okio.use
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Tests [InMemoryCookieJar] end-to-end against the local [TestServer]: a cookie set on one request must be
 * stored and resent on a later request to the same host.
 */
class InMemoryCookieJarTest {

    private fun get(url: String) = RequestBuilder().url(url).get().build()

    @Test
    fun storesAndResendsCookies() = runTest {
        val client = KmpHttpClient.create {
            defaultExecuteTimeout = 30.seconds
            sslConfig = testServerSslConfig()
            cookieJar = InMemoryCookieJar()
        }

        // the server sets the cookie (then redirects); the jar should persist it across calls.
        client.execute(get(TestServer.url("/cookies/set?kmphttp=works"))).use {
            assertEquals(200, it.code)
        }

        // A later, independent request must carry the stored cookie back.
        client.execute(get(TestServer.url("/cookies"))).use { response ->
            assertEquals(200, response.code)
            val body = response.body.utf8String()
            assertContains(body, "kmphttp")
            assertContains(body, "works")
        }
    }

    @Test
    fun defaultJarPersistsNothing() = runTest {
        // Default is CookieJar.NO_COOKIES: nothing is stored between calls.
        val client = KmpHttpClient.create {
            defaultExecuteTimeout = 30.seconds
            sslConfig = testServerSslConfig()
        }

        client.execute(get(TestServer.url("/cookies/set?ghost=boo"))).use {
            assertEquals(200, it.code)
        }
        client.execute(get(TestServer.url("/cookies"))).use { response ->
            assertEquals(200, response.code)
            assertFalse(response.body.utf8String().contains("ghost"), "no cookie should persist")
        }
    }
}
