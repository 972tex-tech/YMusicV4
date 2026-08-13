package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.YtSectionHeader
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Discover's Music tab over a live charts.youtube.com capture: the charts request goes to its own
 * host with its own client, and the sections — which the response has no titles for — render with
 * the app's localized titles (the engine sends a `titleKey`, the app resolves it).
 */
@OptIn(ExperimentalTestApi::class)
class TrendingScreenTest {

    @Test
    fun music_tab_shows_the_region_charts_with_localized_section_titles() {
        Locale.setDefault(Locale.ENGLISH)
        val chartsJson = checkNotNull(javaClass.classLoader.getResource("yt-charts-live.json")).readText()
        val chartsRequest = AtomicReference<String?>()
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                url.contains("charts.youtube.com") -> {
                    chartsRequest.set(body)
                    textResponse(request, chartsJson, "application/json")
                }
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                onNodeWithContentDescription("Music").performClick()

                // The chart's own header — synthesized by the engine, localized here.
                waitUntilAtLeastOneExists(hasText("Trending Music Videos"), 20_000)
                // Its first entry: title, the credited artist, the chart position.
                waitUntilAtLeastOneExists(hasText("Dead Fresh", substring = true), 10_000)
                waitUntilAtLeastOneExists(hasText("Lil Baby · #1", substring = true), 10_000)
                // Collapsed, so every chart's header is on screen at once — the third one included.
                waitUntilAtLeastOneExists(hasText("Daily Top Music Videos"), 10_000)

                val sent = assertNotNull(chartsRequest.get(), "the charts host was never called")
                assertTrue(sent.contains(""""clientName":"WEB_MUSIC_ANALYTICS""""), sent)
                assertTrue(sent.contains(""""browseId":"FEmusic_analytics_charts_home""""), sent)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    /** Each chart opens as a teaser: two entries plus a "Show more" that reveals the rest. */
    @Test
    fun charts_start_collapsed_and_expand_on_demand() {
        Locale.setDefault(Locale.ENGLISH)
        val chartsJson = checkNotNull(javaClass.classLoader.getResource("yt-charts-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                url.contains("charts.youtube.com") -> textResponse(request, chartsJson, "application/json")
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                onNodeWithContentDescription("Music").performClick()
                waitUntilAtLeastOneExists(hasText("Trending Music Videos"), 20_000)

                // The trending chart holds 10 entries in the capture. How many the teaser shows is
                // the app's responsive call (2 in a list, one row in a grid) — what matters is that
                // entry #4 is behind the button either way, and comes into view when it's pressed.
                onAllNodesWithText("Quiet on the Creek", substring = true).assertCountEquals(0)
                onAllNodesWithText("Show more", substring = true).onFirst().performClick()

                waitUntilAtLeastOneExists(hasText("Quiet on the Creek", substring = true), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    /** A newer bundle inventing a chart this build has no string for still shows its title. */
    @Test
    fun the_engine_synthesized_title_is_the_fallback_for_an_unknown_key() {
        Locale.setDefault(Locale.ENGLISH)
        runComposeUiTest {
            setContent {
                SectionHeader(
                    YtSectionHeader(title = "Some Future Chart", titleKey = "chart_from_a_newer_bundle"),
                )
            }
            waitUntilAtLeastOneExists(hasText("Some Future Chart"), 10_000)
        }
    }
}
