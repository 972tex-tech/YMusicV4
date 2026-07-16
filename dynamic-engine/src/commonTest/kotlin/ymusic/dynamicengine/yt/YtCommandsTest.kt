package ymusic.dynamicengine.yt

import kotlinx.coroutines.test.runTest
import ymusic.dynamicengine.CannedResponse
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.EngineError
import ymusic.dynamicengine.FakeHttpClient
import ymusic.dynamicengine.readAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The yt scraping commands end to end over the PRODUCTION bundle: Kotlin args → engine JS →
 * (canned) InnerTube HTTP → parser → typed page back. Fixtures are trimmed captures of live
 * responses (YtFixtures.kt), so the parser is exercised against real current shapes.
 */
class YtCommandsTest {

    /** The engine serves the request body over a lazy pipe that dies with the exchange — capture
     *  it inside the handler, where it is still readable. */
    private var sentBody: String? = null

    private suspend fun manager(handler: suspend (kmphttp.Request) -> kmphttp.Response): Pair<DynamicEngineManager, FakeHttpClient> {
        sentBody = null
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                // The session bootstrap's homepage fetch — serve the ytcfg so scraping succeeds.
                CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                sentBody = request.body?.readAll()?.decodeToString()
                handler(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1)
        m.start()
        return m to client
    }

    private fun jsonResponse(body: String) = CannedResponse.ofText(
        body,
        headers = listOf("Content-Type" to "application/json"),
    )

    @Test
    fun search_parses_videos_channels_and_lockups() = runTest {
        val (m, client) = manager { jsonResponse(SEARCH_FIXTURE).build(it) }
        try {
            val page = m.search(query = "son tung mtp")

            val req = assertNotNull(client.lastRequest)
            assertEquals("POST", req.method)
            assertTrue(req.url.toString().contains("/youtubei/v1/search?prettyPrint=false"), req.url.toString())
            assertEquals("1", req.headers["X-Youtube-Client-Name"])
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""query":"son tung mtp""""), sent)
            assertTrue(sent.contains(""""clientName":"WEB""""), sent)

            assertEquals(1, page.sections.size, "search is one flat list section: $page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            val items = section.items
            assertEquals(4, items.size, "$page")
            val video = items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, video.kind)
            assertEquals("yuuWdm5tBD0", video.id)
            assertTrue(video.title.contains("COME MY WAY"), video.title)
            assertEquals("Sơn Tùng M-TP Official", video.subtitleLine1)
            assertEquals("335,929 views · 49 minutes ago", video.subtitleLine2)
            assertEquals("UClyA28-01x4z60eWQ2kiNbA", video.channelId)
            assertEquals("4:13", video.duration)
            assertNotNull(video.thumb)

            val channel = items.first { it.kind == YtFeedItem.KIND_CHANNEL }
            assertTrue(channel.id.startsWith("UC"), channel.id)
            assertEquals("11.8M subscribers", channel.subtitleLine1)
            assertEquals("Subscribe", channel.ctaText)

            val mix = items.first { it.kind == YtFeedItem.KIND_PLAYLIST }
            assertTrue(mix.id.startsWith("RD"), mix.id)
            assertTrue(mix.title.startsWith("Mix"), mix.title)
            assertNotNull(mix.thumb, "collection thumbnail must resolve")

            assertNotNull(page.continuation, "search page must carry a continuation")
        } finally {
            m.close()
        }
    }

    @Test
    fun browse_parses_rich_grid_chips_and_nested_shelves() = runTest {
        val (m, client) = manager { jsonResponse(BROWSE_FIXTURE).build(it) }
        try {
            val page = m.browse(browseId = "FEwhat_to_watch", ctx = YtContext(hl = "vi", gl = "VN", visitorData = "vd123"))

            val req = assertNotNull(client.lastRequest)
            assertEquals("vd123", req.headers["X-Goog-Visitor-Id"])
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"FEwhat_to_watch""""), sent)
            assertTrue(sent.contains(""""hl":"vi""""), sent)
            assertTrue(sent.contains(""""visitorData":"vd123""""), sent)

            assertEquals(1, page.sections.size, "$page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_GRID, section.layout, "rich-grid feed → grid layout")
            assertEquals(2, section.items.size, "one direct rich item + one inside the nested shelf: $page")
            assertEquals("browse-cont-token", page.continuation)
            assertEquals(2, page.chips.size)
            assertEquals("All", page.chips[0].title)
            assertTrue(page.chips[0].selected)
            assertEquals("chip-music-token", page.chips[1].token)
        } finally {
            m.close()
        }
    }

    @Test
    fun browse_groups_a_titled_shelf_into_its_own_section() = runTest {
        val (m, _) = manager { jsonResponse(HOME_SHELF_FIXTURE).build(it) }
        try {
            val page = m.browse(browseId = "FEwhat_to_watch")

            assertEquals(2, page.sections.size, "loose grid + titled shelf: $page")

            val grid = page.sections[0]
            assertNull(grid.header, "the loose grid has no header")
            assertEquals(YtSection.LAYOUT_GRID, grid.layout)
            assertEquals(listOf("AAAAAAAAAAA"), grid.items.map { it.id })

            val shelf = page.sections[1]
            val header = assertNotNull(shelf.header, "the titled shelf carries a header")
            assertEquals("For you", header.title)
            assertEquals("shelf-cta-token", header.ctaToken)
            assertEquals(YtSection.LAYOUT_GRID, shelf.layout)
            assertEquals(listOf("BBBBBBBBBBB"), shelf.items.map { it.id })

            assertEquals("page-cont", page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun watch_next_parses_lockups_and_autoplay() = runTest {
        val (m, _) = manager { jsonResponse(NEXT_FIXTURE).build(it) }
        try {
            val page = m.watchNext(videoId = "yuuWdm5tBD0")

            assertEquals(1, page.sections.size, "$page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            assertEquals(2, section.items.size, "$page")
            val first = section.items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, first.kind)
            assertEquals("SlQR9iu09bQ", first.id)
            assertTrue(first.title.contains("COME MY WAY"), first.title)
            assertEquals("Sơn Tùng M-TP Official and Tyga", first.subtitleLine1)
            assertEquals("3:55", first.duration)
            assertEquals("38M views · 1 month ago", first.subtitleLine2)
            assertEquals("kV1Nxa9tLuY", page.autoplayVideoId)
            assertNotNull(page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun suggest_parses_the_firefox_array() = runTest {
        val (m, client) = manager {
            CannedResponse.ofText("""["son tung",["sơn tùng m-tp","son tung mtp making my way"]]""").build(it)
        }
        try {
            val suggestions = m.searchSuggestions("son tung")
            assertEquals(listOf("sơn tùng m-tp", "son tung mtp making my way"), suggestions)
            val url = assertNotNull(client.lastRequest).url.toString()
            assertTrue(url.contains("client=firefox"), url)
            assertTrue(url.contains("q=son%20tung"), url)
        } finally {
            m.close()
        }
    }

    @Test
    fun http_failure_crosses_as_typed_network_error() = runTest {
        val (m, _) = manager { throw okio.IOException("connect refused") }
        try {
            assertFailsWith<EngineError.Network> { m.search(query = "anything") }
        } finally {
            m.close()
        }
    }

    @Test
    fun upstream_http_status_error_is_a_network_error() = runTest {
        val (m, _) = manager { CannedResponse.ofText("""{"error":{"code":400}}""", status = 400).build(it) }
        try {
            assertFailsWith<EngineError.Network> { m.browse(browseId = "FEtrending") }
        } finally {
            m.close()
        }
    }

    @Test
    fun missing_required_arg_is_a_parse_error() = runTest {
        val (m, _) = manager { jsonResponse(SEARCH_FIXTURE).build(it) }
        try {
            assertFailsWith<EngineError.Parse> { m.search() }
        } finally {
            m.close()
        }
    }
}
