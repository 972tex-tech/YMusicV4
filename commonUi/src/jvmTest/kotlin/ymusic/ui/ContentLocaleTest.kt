package ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlinx.coroutines.runBlocking
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.settings.AppSettings
import ymusic.settings.InMemorySettingsStore
import ymusic.settings.put
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * hl/gl end to end: the region YouTube reports for the connection (the bootstrap ytcfg's GL) seeds
 * the InnerTube context without the user doing anything, and picking a region in Settings both
 * overrides it and throws the cached feed away — the charts are region-driven, so a stale page would
 * be the wrong country's.
 */
@OptIn(ExperimentalTestApi::class)
class ContentLocaleTest {

    /** The shared fixture's ytcfg with YouTube's own verdict on the connection added: a US device
     *  browsing from Vietnam, which is exactly the case the region ladder exists for. */
    private val HOMEPAGE_IN_VN = HOMEPAGE_FIXTURE.replace(
        """"VISITOR_DATA":""",
        """"GL":"VN","HL":"en","VISITOR_DATA":""",
    )

    private fun chartsClient(requests: MutableList<String>) = CannedHttp { request, body ->
        val url = request.url.toString()
        when {
            url.contains("charts.youtube.com") -> {
                requests.add(body.orEmpty())
                textResponse(
                    request,
                    checkNotNull(javaClass.classLoader.getResource("yt-charts-live.json")).readText(),
                    "application/json",
                )
            }
            request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                textResponse(request, HOMEPAGE_IN_VN, "text/html")
            else -> textResponse(request, "{}", "application/json")
        }
    }

    @Test
    fun the_region_youtube_reports_seeds_the_context_without_any_user_choice() {
        Locale.setDefault(Locale.US)
        val charts = CopyOnWriteArrayList<String>()
        val engines = DynamicEngineManager(chartsClient(charts), appVersion = 1)
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val settings = InMemorySettingsStore()
                val graph = AppGraph(engines = engines, player = StubPlayer(), settings = settings, playerSurface = {})
                setContent { App(graph) }

                onNodeWithContentDescription("Music").performClick()
                waitUntilAtLeastOneExists(hasText("Trending Music Videos"), 20_000)
                waitUntil(timeoutMillis = 10_000) { charts.isNotEmpty() }

                // The device is en-US and the user picked nothing, yet the charts come from VN:
                // the ytcfg's GL is YouTube's own IP-derived answer and outranks the device for the
                // region. The language still follows the device — YouTube only echoes back the
                // Accept-Language we sent it.
                waitUntil(timeoutMillis = 10_000) { charts.last().contains(""""gl":"VN"""") }
                assertEquals("VN", settings.string(AppSettings.glDetected.key, "").value)
                assertTrue(charts.last().contains(""""hl":"en""""), charts.last())
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun choosing_a_region_overrides_it_and_drops_the_cached_feed() {
        Locale.setDefault(Locale.US)
        val charts = CopyOnWriteArrayList<String>()
        val engines = DynamicEngineManager(chartsClient(charts), appVersion = 1)
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val settings = InMemorySettingsStore()
                val graph = AppGraph(engines = engines, player = StubPlayer(), settings = settings, playerSurface = {})
                setContent { App(graph) }

                onNodeWithContentDescription("Music").performClick()
                waitUntilAtLeastOneExists(hasText("Trending Music Videos"), 20_000)
                waitUntil(timeoutMillis = 10_000) { charts.isNotEmpty() }
                val before = charts.size

                runBlocking { settings.put(AppSettings.gl, "JP") }

                waitUntil(timeoutMillis = 10_000) { charts.size > before }
                assertTrue(charts.last().contains(""""gl":"JP""""), charts.last())
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
