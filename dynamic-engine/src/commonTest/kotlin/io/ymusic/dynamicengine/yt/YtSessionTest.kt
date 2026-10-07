package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kotlinx.coroutines.test.runTest
import projectcommon.json.objectOrNull
import projectcommon.json.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The InnerTube session bootstrap (engine-js session.ts) over the production bundle: the engine
 * scrapes youtube.com once, reuses it in-memory, and re-scrapes only when a call 403s. No cache
 * store is wired here, so the host Cache is capability-inert — the engine degrades to a live scrape.
 */
class YtSessionTest {

    private fun homepage(request: kmphttp.Request): kmphttp.Response =
        CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)

    private fun searchOk(request: kmphttp.Request, status: Int = 200): kmphttp.Response =
        CannedResponse.ofText(SEARCH_FIXTURE, status = status, headers = listOf("Content-Type" to "application/json"))
            .build(request)

    private fun isHomepage(request: kmphttp.Request): Boolean {
        val url = request.url.toString()
        return request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")
    }

    @Test
    fun the_homepage_is_scraped_once_and_reused() = runTest {
        var homepageHits = 0
        var apiHits = 0
        val client = FakeHttpClient { request ->
            if (isHomepage(request)) {
                homepageHits++
                homepage(request)
            } else {
                apiHits++
                searchOk(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            m.search(query = "a")
            m.search(query = "b")
            assertEquals(1, homepageHits, "the session is scraped once and reused in-memory")
            assertEquals(2, apiHits, "each search still hits the API")
        } finally {
            m.close()
        }
    }

    @Test
    fun a_403_refreshes_the_session_and_retries_once() = runTest {
        var homepageHits = 0
        var apiHits = 0
        val client = FakeHttpClient { request ->
            if (isHomepage(request)) {
                homepageHits++
                homepage(request)
            } else {
                apiHits++
                searchOk(request, status = if (apiHits == 1) 403 else 200)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            val page = m.search(query = "a")
            assertEquals(2, homepageHits, "403 forces a re-scrape of the session")
            assertEquals(2, apiHits, "the API call is retried once after the refresh")
            assertTrue(page.sections.any { it.items.isNotEmpty() }, "the retry's result is parsed")
        } finally {
            m.close()
        }
    }

    @Test
    fun get_player_config_is_parsed_from_the_homepage() = runTest {
        val client = FakeHttpClient { request ->
            if (isHomepage(request)) homepage(request)
            else searchOk(request)
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            val rec = m.use { engine ->
                engine.jsonCommand("get_player_config", { }) { it.readObject() }
            }
            assertEquals("test-event-id", rec.stringOrNull("eventId"))
            assertTrue(rec.stringOrNull("jsUrl").orEmpty().contains("player_es6"), rec.toString())
            val bg = rec.objectOrNull("bgChallenge")
            assertEquals("TESTPROG", bg?.stringOrNull("program"))
            assertEquals("trayride", bg?.stringOrNull("globalName"))
            assertTrue(rec.objectOrNull("ytcfg")?.isNotEmpty() == true, rec.toString())
        } finally {
            m.close()
        }
    }
}
