package io.ymusic.dynamicengine

import kmphttp.CookieJar
import kmphttp.InMemoryCookieJar
import kmphttp.loadForRequest
import kmphttp.parseCookie
import kmphttp.saveFromResponse
import kmphttp.toHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cookie protocol end to end: the bundle's `Cookies` client (engine-js/src/protocol/cookie.ts)
 * over the `"cookie/get"` / `"cookie/set"` commands, served on the HTTP client's own
 * [kmphttp.CookieJar]. Driven through the test-bundle fixtures
 * (engine-js/src/test/fixtures/cookie.ts, the `test/cookie/…` commands) — each opens a real
 * `Cookies.*` call and reports the outcome. Backed by an [InMemoryCookieJar] (or [throwingCookieJar]
 * for the failure path), the same jar an app would configure on its client.
 */
class CookieProtocolTest {

    /** Send the JSON [head], read the fixture's reported outcome to EOF. */
    private suspend fun DynamicEngine.drive(command: String, head: String): String {
        val pipe = openPipe(command)
        return try {
            val buf = Buffer().writeUtf8(head)
            pipe.sink.send(buf, buf.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    private suspend fun engineOn(jar: CookieJar) =
        createTestEngine(FakeHttpClient(CannedResponse.ofText("")).apply { cookieJar = jar })

    @Test
    fun get_returns_every_cookie_the_jar_holds_for_the_url() = runTest {
        val jar = InMemoryCookieJar()
        val url = "https://youtube.com/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOfNotNull(parseCookie(url, "SID=abc; Path=/"), parseCookie(url, "HSID=xyz; Path=/")),
        )
        val e = engineOn(jar)
        try {
            assertEquals("HSID=xyz; SID=abc", e.drive("test/cookie/get", """{"url":"https://youtube.com/"}"""))
        } finally {
            e.close()
        }
    }

    @Test
    fun get_on_an_empty_jar_is_empty() = runTest {
        val e = engineOn(InMemoryCookieJar())
        try {
            assertEquals("", e.drive("test/cookie/get", """{"url":"https://unknown.example/"}"""))
        } finally {
            e.close()
        }
    }

    @Test
    fun set_parses_the_header_into_the_jar() = runTest {
        val jar = InMemoryCookieJar()
        val e = engineOn(jar)
        try {
            assertEquals("ok", e.drive("test/cookie/set", """{"url":"https://x.example/","setCookie":["a=b; Path=/"]}"""))
            val stored = jar.loadForRequest("https://x.example/".toHttpUrl())
            assertEquals("b", stored.firstOrNull { it.name == "a" }?.value)
        } finally {
            e.close()
        }
    }

    @Test
    fun set_accepts_an_array_of_multiple_headers() = runTest {
        val e = engineOn(InMemoryCookieJar())
        try {
            assertEquals(
                "HSID=h1; SID=s1",
                e.drive(
                    "test/cookie/set-then-get",
                    """{"url":"https://youtube.com/","setCookie":["SID=s1; Path=/","HSID=h1; Path=/"]}""",
                ),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun an_invalid_url_resets_with_E20040() = runTest {
        val e = engineOn(InMemoryCookieJar())
        try {
            assertEquals(
                "E20040|cookie request has an invalid 'url': not-a-url",
                e.drive("test/cookie/get-err", """{"url":"not-a-url"}"""),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun a_missing_url_resets_with_E20040() = runTest {
        val e = engineOn(InMemoryCookieJar())
        try {
            assertEquals("E20040|cookie request missing 'url'", e.drive("test/cookie/get-err", """{"url":""}"""))
        } finally {
            e.close()
        }
    }

    /** The zombie regression: a store that never answers parks ONLY its own command — the jar
     *  call rides Dispatchers.IO (the engine-thread rule, AGENTS.md), so the engine thread stays
     *  free and an unrelated command still round-trips while the cookie command is wedged. */
    @Test
    fun a_wedged_jar_blocks_only_its_command_never_the_engine() = runTest {
        val e = engineOn(wedgedCookieJar())
        try {
            val wedged = launch(Dispatchers.Default) {
                runCatching { e.drive("test/cookie/get", """{"url":"https://x.example/"}""") }
            }
            // Real time, not virtual: let the cookie command reach the parked jar call first.
            withContext(Dispatchers.Default) { delay(200) }
            assertEquals("app=1", e.drive("test/http/versions", "{}"))
            wedged.cancel()
        } finally {
            e.close()
        }
    }

    @Test
    fun a_jar_failure_resets_with_E20041() = runTest {
        val e = engineOn(throwingCookieJar())
        try {
            assertEquals("E20041|cookie jar unavailable", e.drive("test/cookie/get-err", """{"url":"https://x.example/"}"""))
        } finally {
            e.close()
        }
    }
}
