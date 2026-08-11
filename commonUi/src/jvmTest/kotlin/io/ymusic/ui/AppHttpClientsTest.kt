package io.ymusic.ui

import kmphttp.CookieJar
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The app's clients must all sit on ONE disk cache: a second `KmpHttpClient.create {}` silently
 * drops it, and a second Cache over the same directory corrupts it.
 */
class AppHttpClientsTest {

    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-http-cache-test"

    @AfterTest
    fun cleanup() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun the_engine_client_is_cached_and_carries_no_cookie_jar() {
        val http = AppHttpClients(dir)
        assertNotNull(http.engine.cache, "the engine client must have a disk cache")
        assertEquals(CookieJar.NO_COOKIES, http.engine.cookieJar, "cookie policy lives in the engine JS")
    }

    /** The wiring is only worth anything if a second GET is served from disk: a local server counts
     *  the hits it actually receives. */
    @Test
    fun a_cacheable_response_is_served_from_disk_the_second_time() = kotlinx.coroutines.runBlocking {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        server.createContext("/feed") { exchange ->
            hits.incrementAndGet()
            val body = """{"ok":true}""".encodeToByteArray()
            exchange.responseHeaders.add("Cache-Control", "max-age=600")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = AppHttpClients(dir).engine
            val url = "http://127.0.0.1:${server.address.port}/feed"
            repeat(2) {
                val response = client.execute(kmphttp.RequestBuilder().url(url).get().build())
                assertEquals("""{"ok":true}""", response.body?.string())
            }
            assertEquals(1, hits.get(), "the second GET must come from the disk cache")
        } finally {
            server.stop(0)
        }
    }
}
