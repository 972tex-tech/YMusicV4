package io.ymusic.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.yt.applyYtLocale
import io.ymusic.settings.InMemorySettingsStore
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
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
            request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") -> {
                // Echo a PREF gl pick back in the ytcfg, as the live server does — YouTube owns
                // the working region value and the session parses this answer.
                val picked = Regex("""gl=(\w+)""").find(request.headers["Cookie"].orEmpty())?.groupValues?.get(1)
                val page = if (picked == null) {
                    HOMEPAGE_IN_VN
                } else {
                    HOMEPAGE_IN_VN.replace(""""GL":"VN"""", """"GL":"${picked.uppercase()}"""")
                }
                textResponse(request, page, "text/html")
            }
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
                // the ENGINE's ladder puts YouTube's own IP-derived answer (the ytcfg GL) above
                // the device for the region, while the language follows the device. The app
                // asserted nothing — no setting, no context parameter.
                waitUntil(timeoutMillis = 10_000) { charts.last().contains(""""gl":"VN"""") }
                assertTrue(charts.last().contains(""""hl":"en""""), charts.last())
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun the_content_region_picker_is_a_dialog_listing_youtubes_menu() {
        Locale.setDefault(Locale.US)
        // Held until the dialog is open, so the fetch-in-flight state is observable.
        val menuGate = java.util.concurrent.CompletableFuture<Unit>()
        val engines = DynamicEngineManager(
            CannedHttp { request, _ ->
                val url = request.url.toString()
                when {
                    url.contains("account/account_menu") -> {
                        menuGate.get(20, java.util.concurrent.TimeUnit.SECONDS)
                        textResponse(request, ACCOUNT_MENU_FIXTURE, "application/json")
                    }
                    request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") -> {
                        // Echo a PREF gl pick back in the ytcfg, as the live server does.
                        val picked = Regex("""gl=(\w+)""").find(request.headers["Cookie"].orEmpty())?.groupValues?.get(1)
                        val page = if (picked == null) {
                            HOMEPAGE_IN_VN
                        } else {
                            HOMEPAGE_IN_VN.replace(""""GL":"VN"""", """"GL":"${picked.uppercase()}"""")
                        }
                        textResponse(request, page, "text/html")
                    }
                    else -> textResponse(request, "{}", "application/json")
                }
            },
            appVersion = 1,
        )
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val settings = InMemorySettingsStore()
                val graph = AppGraph(engines = engines, player = StubPlayer(), settings = settings, playerSurface = {})
                setContent {
                    App(graph, androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AppSection.Settings) })
                }

                // The ~100-entry menu opens as a dialog off the row (a dropdown can't hold it).
                // While the fetch is still in flight there is nothing to offer beyond Automatic,
                // and the dialog must say so with a spinner instead of looking complete.
                waitUntilAtLeastOneExists(hasText("Content region"), 20_000)
                onNodeWithText("Content region").performClick()
                waitUntilAtLeastOneExists(hasText("Cancel"), 10_000)
                onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()

                menuGate.complete(Unit)
                waitUntilAtLeastOneExists(hasText("Hoa Kỳ"), 10_000)
                onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()

                // Picking reports to the ENGINE (yt/locale/apply) — no app setting is written.
                // The dialog closes and the row re-reads the pick from the engine.
                onNodeWithText("Hoa Kỳ").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodes(hasText("Cancel")).fetchSemanticsNodes().isEmpty()
                }
                waitUntilAtLeastOneExists(hasText("Hoa Kỳ"), 10_000)
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

                // What the Settings pickers do on a pick: report it to the engine, then announce
                // "the locale changed" — the app never holds the value itself.
                runBlocking { graph.engines.applyYtLocale(gl = "JP") }
                graph.localeEpoch.value++

                waitUntil(timeoutMillis = 10_000) { charts.size > before }
                assertTrue(charts.last().contains(""""gl":"JP""""), charts.last())
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
