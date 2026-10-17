package kmphttp

import okio.use
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ranged GET through a 302 must reach the redirect target with its `Range` header intact and
 * come back as the target's 206 slice — how a resumed download through a release redirect works.
 */
internal suspend fun assertRangeSurvivesRedirect(client: KmpHttpClient) {
    val size = 1000
    val from = 600
    val request = RequestBuilder()
        .url(TestServer.url("/range-redirect/$size"))
        .header("Range", "bytes=$from-")
        .get()
        .build()
    client.execute(request).use { response ->
        assertTrue(response.request.url.toString().endsWith("/range/$size"), "followed to ${response.request.url}")
        assertEquals("bytes=$from-", response.header("X-Received-Range"))
        assertEquals(206, response.code)
        assertEquals("bytes $from-${size - 1}/$size", response.header("Content-Range"))
        val body = response.body.byteString()
        assertEquals(size - from, body.size)
        for (i in 0 until body.size) assertEquals(((from + i) % 251).toByte(), body[i], "byte ${from + i}")
    }
}
