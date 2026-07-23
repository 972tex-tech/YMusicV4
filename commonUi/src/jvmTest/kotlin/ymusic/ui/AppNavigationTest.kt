package ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlinx.coroutines.runBlocking
import ymusic.dynamicengine.DynamicEngineManager
import java.util.Locale
import kotlin.test.Test

/**
 * The whole app shell (App()) navigation, headless: search overlay → results → tapping a playlist
 * must show its detail page IMMEDIATELY (regression: the search overlay rendered above the pushed
 * detail, which only appeared after backing out of search).
 */
@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {

    @Test
    fun search_result_playlist_tap_opens_detail_immediately() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val mixJson = checkNotNull(javaClass.classLoader.getResource("yt-mix-next-live.json")).readText()
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                // The tapped result is the Mix lockup (RDEM… id) → its detail loads via /next.
                url.contains("youtubei/v1/next") -> textResponse(request, mixJson, "application/json")
                // Discover's home browse — an empty page is fine, the test never looks at it.
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1)
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                onNodeWithContentDescription("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()

                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 20_000)
                onNodeWithText("Mix - Sơn Tùng M-TP").performClick()

                // The detail page is on screen right away — its bar title and the /next panel header.
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Adele - Rolling in the Deep (Official Music Video)"), 20_000)

                // Back pops the detail onto the still-loaded SEARCH RESULTS (not Home): the
                // results bar title and the tapped result are right there, no reload.
                onNodeWithContentDescription("Back").performClick()
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 10_000)

                // Back again leaves search for the Discover shell.
                onNodeWithContentDescription("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Home"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun search_icon_on_a_detail_page_brings_the_existing_results_to_front() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val mixJson = checkNotNull(javaClass.classLoader.getResource("yt-mix-next-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                url.contains("youtubei/v1/next") -> textResponse(request, mixJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1)
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                // Search → results → a result's detail page.
                onNodeWithContentDescription("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 20_000)
                onNodeWithText("Mix - Sơn Tùng M-TP").performClick()
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)

                // The detail bar's search icon raises the EXISTING results layer (SINGLE_TOP):
                // the submitted query and its loaded results are right there — no blank input.
                onNodeWithContentDescription("Search").performClick()
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 10_000)

                // The stack reordered (not grew): back lands on the detail page, then Home.
                onNodeWithContentDescription("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)
                onNodeWithContentDescription("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Home"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun each_section_keeps_its_own_stack_across_drawer_switches() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1)
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                // Hoisted so the test can switch sections the way a drawer tap does — over the
                // results layer the drawer only opens by touch edge-swipe, which jvm can't drive.
                val section = androidx.compose.runtime.mutableStateOf(AppSection.Discover)
                setContent { App(graph, section) }

                // Discover: search → submitted results.
                onNodeWithContentDescription("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 20_000)

                // → Library: a lateral switch, NOT a push. Library shows its own surface (no
                // YouTube results), and no YouTube search action — Library's search is a
                // different, local-files flow.
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("Library"), 10_000)
                onNodeWithText("Mix - Sơn Tùng M-TP").assertDoesNotExist()
                onNodeWithContentDescription("Search").assertDoesNotExist()

                // → Discover: the section restores its RETAINED stack — the search results are
                // right there, query and all, not a bare Discover feed.
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP"), 10_000)
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
