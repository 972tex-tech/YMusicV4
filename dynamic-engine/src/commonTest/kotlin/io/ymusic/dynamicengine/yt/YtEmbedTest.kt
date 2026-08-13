package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `yt/embed` command end to end over the production bundle: the WebView player's embed page,
 * fetched with the CALLER's User-Agent verbatim (playback identity) on the engine's cooked cookie
 * path, returned with the rewrites applied and the `INJECT_JS_HERE` boot-script slot in `<head>`.
 */
class YtEmbedTest {

    private val embedFixture =
        "<!DOCTYPE html><html><head>" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'\">" +
            "<link rel=\"preload\" as=\"script\" href=\"/player.js\">" +
            "<title>YouTube</title></head><body></body></html>"

    @Test
    fun embed_page_rides_the_callers_ua_and_carries_the_inject_slot() = runTest {
        var userAgent: String? = null
        var accept: String? = null
        var cookieHeader: String? = null
        val client = FakeHttpClient { request ->
            userAgent = request.header("User-Agent")
            accept = request.header("Accept")
            cookieHeader = request.header("Cookie")
            CannedResponse.ofText(embedFixture, headers = listOf("Content-Type" to "text/html")).build(request)
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            val html = m.embedPage("Mozilla/5.0 (Linux; Android 14) SomeWebView/1.0")
            // The WebView's UA verbatim — never the engine's desktop scraping UA.
            assertEquals("Mozilla/5.0 (Linux; Android 14) SomeWebView/1.0", userAgent)
            assertEquals("text/html", accept)
            // The cookie path is the engine's cooked one: consent is synthesized even from empty.
            assertTrue(cookieHeader.orEmpty().contains("SOCS="), "cooked Cookie header, got: $cookieHeader")
            // The boot-script slot, first in <head>, before YouTube's own tags.
            assertTrue(
                html.contains("<head><script>\nINJECT_JS_HERE\n</script><meta"),
                "inject slot must lead <head>, got: $html",
            )
            assertTrue(html.contains("content-security-policy-disabled"), html)
            assertTrue(html.contains("as=\"script-blocked\""), html)
        } finally {
            m.close()
        }
    }

    @Test
    fun a_non_ok_embed_fetch_is_a_typed_network_error() = runTest {
        val client = FakeHttpClient { request ->
            CannedResponse.ofText("nope", status = 403).build(request)
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            val e = assertFailsWith<EngineError> { m.embedPage("UA") }
            assertEquals("network", e.code)
        } finally {
            m.close()
        }
    }
}
