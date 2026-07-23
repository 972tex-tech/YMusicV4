package ymusic.dynamicengine.yt

import kotlinx.coroutines.launch
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
    fun cancelling_an_inflight_command_must_not_wedge_later_commands() = runTest {
        // Repro of the Android zombie state (2026-07-23): a command cancelled mid-flight (a pager
        // refresh / suggestion debounce cancelling its predecessor) must not leave the engine's
        // shared paths (session, cookie pipe) held — later commands must still complete.
        val firstRequestStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var slowServed = false
        val (m, _) = manager { request ->
            if (!slowServed) {
                slowServed = true
                firstRequestStarted.complete(Unit)
                gate.await() // hold the first search's HTTP exchange open until cancelled
            }
            jsonResponse(SEARCH_FIXTURE).build(request)
        }
        try {
            val stuck = backgroundScope.launch { m.search(query = "held") }
            firstRequestStarted.await()
            stuck.cancel() // cancel mid-HTTP, inside the engine command
            stuck.join()
            gate.complete(Unit)

            // The engine must serve the next command normally — pre-fix this hung forever.
            // Real-time timeout: the engine works on its own thread, outside the virtual clock.
            val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(30_000) { m.search(query = "son tung mtp") }
            }
            assertTrue(page.sections.isNotEmpty(), "$page")
        } finally {
            m.close()
        }
    }

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

            assertEquals(2, page.sections.size, "flat results + the titled shelf: $page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            val items = section.items
            assertEquals(4, items.size, "$page")
            // Music-first ranking: the artist-badged video and the Mix bubble above the plain
            // verified video and the channel row, each group keeping the server's relative order
            // (server order was video · video · channel · mix).
            assertEquals(
                listOf("yuuWdm5tBD0", "RDEMeyKnYmm7ScRhnRO9nwOSNA", "FN7ALfpGxiI", "UClyA28-01x4z60eWQ2kiNbA"),
                items.map { it.id },
                "$page",
            )
            val video = items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, video.kind)
            assertEquals("yuuWdm5tBD0", video.id)
            assertTrue(video.title.contains("COME MY WAY"), video.title)
            assertEquals("Sơn Tùng M-TP Official", video.subtitleLine1)
            assertEquals("335,929 views · 49 minutes ago", video.subtitleLine2)
            assertEquals("UClyA28-01x4z60eWQ2kiNbA", video.channelId)
            assertEquals("4:13", video.duration)
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, video.ownerBadge, "$video")
            assertTrue(!video.musicVideo, "no MUSIC overlay on this one: $video")
            assertEquals(YtFeedItem.OWNER_BADGE_VERIFIED, items[2].ownerBadge, "${items[2]}")
            val thumb = video.thumbs.single()
            assertTrue(thumb.url.startsWith("https://i.ytimg.com/vi/yuuWdm5tBD0/"), thumb.url)
            assertEquals(720, thumb.w)
            assertEquals(404, thumb.h)

            val channel = items.first { it.kind == YtFeedItem.KIND_CHANNEL }
            assertTrue(channel.id.startsWith("UC"), channel.id)
            assertEquals("11.8M subscribers", channel.subtitleLine1)
            assertEquals("Subscribe", channel.ctaText)
            assertEquals("/channel/${channel.id}", channel.url, "openable items carry their app url")

            val mix = items.first { it.kind == YtFeedItem.KIND_PLAYLIST }
            assertTrue(mix.id.startsWith("RD"), mix.id)
            assertEquals("/playlist?list=${mix.id}", mix.url, "$mix")
            assertTrue(mix.title.startsWith("Mix"), mix.title)
            assertTrue(mix.thumbs.isNotEmpty(), "collection thumbnail must resolve: $mix")
            // The count overlay is what visually separates a playlist row from a video row.
            assertEquals("25 videos", mix.itemCount, "$mix")

            // Shelf videos (gridVideoRenderer) have no lengthText — the duration must come from
            // the time-status thumbnail overlay.
            val shelf = page.sections[1]
            assertEquals("Popular songs", shelf.header?.title, "$shelf")
            val shelfVideo = shelf.items.single()
            assertEquals("3:51", shelfVideo.duration, "$shelfVideo")
            assertEquals("Shelf Channel", shelfVideo.subtitleLine1)
            // The MUSIC-iconed time overlay marks a music video.
            assertTrue(shelfVideo.musicVideo, "$shelfVideo")

            assertNotNull(page.continuation, "search page must carry a continuation")

            assertEquals(listOf("Upload date", "Type", "Features"), page.filterGroups.map { it.title }, "$page")
            val uploadDate = page.filterGroups[0]
            assertEquals(listOf("Last hour", "Today"), uploadDate.options.map { it.label })
            assertEquals("EgIIAQ%3D%3D", uploadDate.options[0].params)
            val type = page.filterGroups[1]
            val selectedVideo = type.options.first { it.label == "Video" }
            assertTrue(selectedVideo.selected, "$selectedVideo")
            assertEquals("EgIQAQ%3D%3D", selectedVideo.params)
            // The url-sp fallback carrier (no searchEndpoint on this one).
            assertEquals("EgIQAg%253D%253D", type.options.first { it.label == "Channel" }.params)
            val purchased = page.filterGroups[2].options.single()
            assertTrue(purchased.disabled, "$purchased")
            assertEquals(null, purchased.params)

            // The header chip bar: a chip's endpoint is a fresh search (query+params), wrapped as
            // an engine-private token so the app's token-only chip pipeline round-trips it.
            assertEquals(listOf("All", "Shorts"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            assertEquals(null, page.chips[0].token, "the active chip has no endpoint")
            val chipToken = assertNotNull(page.chips[1].token)
            assertTrue(chipToken.startsWith("ysq1:"), chipToken)

            m.search(continuation = chipToken)
            val sent3 = assertNotNull(sentBody)
            assertTrue(sent3.contains(""""query":"son tung mtp""""), sent3)
            assertTrue(sent3.contains(""""params":"EgIQAQ%3D%3D""""), sent3)
            assertTrue(!sent3.contains(""""continuation""""), sent3)
        } finally {
            m.close()
        }
    }

    @Test
    fun artist_search_parses_the_universal_watch_card() = runTest {
        val (m, _) = manager { jsonResponse(ARTIST_SEARCH_FIXTURE).build(it) }
        try {
            val page = m.search(query = "bruno mars")

            // Card sections lead the page: artist+Mix, then Albums (near the top, collapsed behind
            // the show-more toggle), then top songs — then the primary results.
            assertEquals(4, page.sections.size, "$page")

            val lead = page.sections[0].items
            assertEquals(2, lead.size, "$lead")
            val artist = lead[0]
            assertEquals(YtFeedItem.KIND_CHANNEL, artist.kind)
            assertEquals("UCoUM-UJ7rirJYP8CQ0EIaHA", artist.id)
            assertEquals("Bruno Mars", artist.title)
            assertEquals("@brunomars", artist.subtitleLine1)
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, artist.ownerBadge, "$artist")
            assertTrue(artist.thumbs.isNotEmpty(), "$artist")
            assertEquals("/channel/UCoUM-UJ7rirJYP8CQ0EIaHA", artist.url)
            val radio = lead[1]
            assertEquals(YtFeedItem.KIND_PLAYLIST, radio.kind)
            assertEquals("RDEMgEsSBB7GgTqDUsqz-X50Iw", radio.id)
            // The hero's seed video rides along as engine-private url state.
            assertEquals("/playlist?list=RDEMgEsSBB7GgTqDUsqz-X50Iw&v=PMivT7MJ41M", radio.url)
            assertEquals("Mix - Bruno Mars", radio.title)
            assertTrue(radio.thumbs.single().url.contains("PMivT7MJ41M"), "seed video thumb: $radio")

            val albums = page.sections[1]
            assertEquals("Albums", albums.header?.title, "$albums")
            assertTrue(albums.collapsible, "album row collapses behind the show-more toggle: $albums")
            // Only the card deep-linking a playlist counts; the plain query refinement is skipped.
            val album = albums.items.single()
            assertEquals(YtFeedItem.KIND_PLAYLIST, album.kind)
            assertTrue(album.id.startsWith("OLAK5uy_"), album.id)
            assertEquals("24K Magic", album.title)
            assertTrue(album.album, "OLAK5uy_ playlists carry the album mark: $album")

            val songs = page.sections[2].items
            assertEquals(listOf("kPa7bsKwL-c", "ekr2nIex040"), songs.map { it.id })
            val song = songs[0]
            assertEquals(YtFeedItem.KIND_VIDEO, song.kind)
            assertEquals("Die With A Smile", song.title)
            assertEquals("4:13", song.duration)
            // watchCardCompactVideoRenderer has no thumbnail — the canonical i.ytimg.com one is used.
            assertTrue(song.thumbs.single().url.contains("/vi/kPa7bsKwL-c/"), "$song")

            val primary = page.sections[3].items
            assertEquals("lY5V4hSLWY8", primary.single().id)
            assertNotNull(page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_detail_parses_page_header_and_tabs_from_the_app_url() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_FIXTURE).build(it) }
        try {
            val page = m.extractChannelDetail("/channel/UCchannelchannelchannel0")

            // The engine decoded the app url into the browse call itself.
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"UCchannelchannelchannel0""""), sent)

            val header = assertNotNull(page.pageHeader, "$page")
            assertEquals("Chan", header.title)
            assertEquals("@chan", header.subtitleLine1)
            assertEquals("1M subscribers · 10 videos", header.subtitleLine2)
            assertEquals("https://a/av.jpg", header.thumbs.single().url)
            assertEquals("https://a/banner.jpg", header.banner.single().url)

            // Content tabRenderer tabs only — the expandableTabRenderer search tab and the Posts
            // (community-params) tab are dropped; the params slug, not the localized title, decides.
            // Each carries its own app url (the round-trip currency); params is informational.
            assertEquals(listOf("Home", "Videos"), page.pageTabs.map { it.title }, "$page")
            assertTrue(page.pageTabs[0].selected, "$page")
            assertTrue(page.pageTabs[0].home, "featured-params tab is the channel front: $page")
            assertTrue(!page.pageTabs[1].home, "$page")
            assertEquals("/channel/UCchannelchannelchannel0?params=EghmZWF0dXJlZA", page.pageTabs[0].url)
            assertEquals("/channel/UCchannelchannelchannel0?params=EgZ2aWRlb3M", page.pageTabs[1].url)

            assertEquals("Channel video", page.sections.single().items.single().title)

            // A tab's url round-trips into a browse with that tab's params.
            m.extractChannelDetail(page.pageTabs[1].url!!)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""browseId":"UCchannelchannelchannel0""""), sent2)
            assertTrue(sent2.contains(""""params":"EgZ2aWRlb3M""""), sent2)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_shorts_tab_parses_shorts_tiles_and_pages_with_a_wrapped_token() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_SHORTS_FIXTURE).build(it) }
        try {
            // The shorts-params url flips the engine into Shorts parsing (skipped everywhere
            // else). The url carries the params the way the engine composed it: the server hands
            // params out URL-ENCODED ("…%3D%3D"), and the url layer encodes on top ("%253D").
            val url = "/channel/UCchannelchannelchannel0?params=EgZzaG9ydHPyBgUKA5oBAA%253D%253D"
            val page = m.extractChannelDetail(url)

            // The browse gets the server's own (still URL-encoded) params back verbatim.
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""params":"EgZzaG9ydHPyBgUKA5oBAA%3D%3D""""), sent)

            val items = page.sections.single().items
            assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), items.map { it.id }, "$page")
            val short = items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, short.kind)
            assertEquals("First short", short.title)
            assertEquals("1M views", short.subtitleLine1)
            assertTrue(short.thumbs.single().url.contains("/vi/AAAAAAAAAAA/"), "$short")

            // The sort bar (chipViewModel generation) parses into chips; on the Shorts tab the
            // tokens are wrapped like the continuation so sorting stays in Shorts mode.
            assertEquals(listOf("Latest", "Popular"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            assertEquals("ychshorts1:sort-latest", page.chips[0].token)
            assertEquals("ychshorts1:sort-popular", page.chips[1].token)

            // The continuation is wrapped with engine-private state so paging stays in Shorts mode…
            val cont = assertNotNull(page.continuation)
            assertTrue(cont.startsWith("ychshorts1:"), cont)
            assertTrue(cont.endsWith("shorts-cont"), cont)

            // …and unwraps back to the raw server token on the next call.
            m.extractChannelDetail(url, continuation = cont)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""continuation":"shorts-cont""""), sent2)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_playlists_tab_surfaces_the_sort_menu_as_chips() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_PLAYLISTS_FIXTURE).build(it) }
        try {
            val url = "/channel/UCchannelchannelchannel0?params=EglwbGF5bGlzdHPyBgQKAkIA"
            val page = m.extractChannelDetail(url)

            assertEquals("Channel playlist", page.sections.single().items.single().title, "$page")

            // The browse-typed sort menu (sortFilterSubMenuRenderer) becomes chips whose tokens
            // wrap the sort's browse params (there is no server continuation for a sort).
            assertEquals(listOf("Date added (newest)", "Last video added"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            val sortToken = assertNotNull(page.chips[1].token)
            assertTrue(sortToken.startsWith("ychp1:"), sortToken)

            // Clicking a sort chip re-browses the channel with the wrapped params — no continuation.
            m.extractChannelDetail(url, continuation = sortToken)
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"UCchannelchannelchannel0""""), sent)
            assertTrue(sent.contains(""""params":"EglwbGF5bGlzdHMYBCABMAHyBgQKAkIA""""), sent)
            assertTrue(!sent.contains(""""continuation""""), sent)
        } finally {
            m.close()
        }
    }

    @Test
    fun home_feed_parses_rich_grid_chips_and_nested_shelves() = runTest {
        val (m, client) = manager { jsonResponse(BROWSE_FIXTURE).build(it) }
        try {
            val page = m.extractHomeFeed(ctx = YtContext(hl = "vi", gl = "VN", visitorData = "vd123"))

            val req = assertNotNull(client.lastRequest)
            assertEquals("vd123", req.headers["X-Goog-Visitor-Id"])
            val sent = assertNotNull(sentBody)
            // The front-door browseId is the ENGINE's knowledge — the app never sent one.
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
    fun home_feed_groups_a_titled_shelf_into_its_own_section() = runTest {
        val (m, _) = manager { jsonResponse(HOME_SHELF_FIXTURE).build(it) }
        try {
            val page = m.extractHomeFeed()

            // The fixture also carries a "Shorts" richShelf (shortsLockupViewModel items) — it must
            // not surface as a section.
            assertEquals(3, page.sections.size, "loose grid + two titled shelves, Shorts dropped: $page")
            assertTrue(page.sections.none { it.header?.title == "Shorts" }, "$page")

            val grid = page.sections[0]
            assertNull(grid.header, "the loose grid has no header")
            assertEquals(YtSection.LAYOUT_GRID, grid.layout)
            assertEquals(listOf("AAAAAAAAAAA"), grid.items.map { it.id })

            val shelf = page.sections[1]
            val header = assertNotNull(shelf.header, "the titled shelf carries a header")
            assertEquals("For you", header.title)
            assertEquals("shelf-cta-token", header.ctaToken)
            // The "View all" browse target becomes an app deeplink (VL prefix stripped: the app's
            // playlist ids are bare, its browse path re-adds VL).
            assertEquals("/playlist?list=PLBcAa442MLAqvWaER_5uj7AVi6FjNe-Zg", header.ctaDeeplink)
            assertEquals(YtSection.LAYOUT_GRID, shelf.layout)
            assertEquals(listOf("BBBBBBBBBBB"), shelf.items.map { it.id })

            // A channel-targeting shelf deep-links the channel WITH its tab params preserved.
            val channelShelf = assertNotNull(page.sections[2].header, "$page")
            assertEquals(
                "/channel/UCchannelchannelchannel0?params=EgZ2aWRlb3M",
                channelShelf.ctaDeeplink,
            )

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
            // Lockup signals: MUSIC badge icon on the thumb, byline attachment badges.
            assertTrue(first.musicVideo, "$first")
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, first.ownerBadge)
            val second = section.items[1]
            assertTrue(!second.musicVideo, "$second")
            assertEquals(YtFeedItem.OWNER_BADGE_VERIFIED, second.ownerBadge)
            assertEquals("kV1Nxa9tLuY", page.autoplayVideoId)
            assertNotNull(page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun mix_playlist_detail_serves_the_watch_panel_and_pages_by_window() = runTest {
        val (m, _) = manager {
            val window = sentBody?.contains("BBBBBBBBBBB") == true
            jsonResponse(if (window) MIX_NEXT_WINDOW_FIXTURE else MIX_NEXT_FIXTURE).build(it)
        }
        try {
            // The app hands back the engine-composed url; the engine alone knows a Mix isn't
            // browsable and must go through /next with the start-radio params. Seedless (RDEM…):
            // playlistId alone must be a valid call.
            val url = "/playlist?list=RDEMtestmix"
            val page = m.extractPlaylistDetail(url)

            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""playlistId":"RDEMtestmix""""), sent)
            assertTrue(sent.contains(""""params":"wAEB""""), sent)

            assertEquals("Mix - Test Radio", page.pageHeader?.title, "$page")
            assertEquals("YouTube", page.pageHeader?.subtitleLine1)
            // Only the panel belongs on the detail page — the response's watch-page related
            // videos are dropped by the engine, not by the app.
            assertEquals(listOf("Mix song one", "Mix song two"), page.sections.single().items.map { it.title })
            assertEquals("3:33", page.sections.single().items[0].duration)
            assertEquals("Artist One", page.sections.single().items[0].subtitleLine1)

            // The infinite panel yields the engine's own opaque continuation; feeding it back
            // slides the window (videoId + playlistIndex) and returns ONLY the fresh tail.
            val token = assertNotNull(page.continuation, "$page")
            val page2 = m.extractPlaylistDetail(url, continuation = token)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""videoId":"BBBBBBBBBBB""""), sent2)
            assertTrue(sent2.contains(""""playlistIndex":1"""), sent2)
            assertEquals(listOf("Mix song three"), page2.sections.single().items.map { it.title }, "$page2")
            assertNotNull(page2.continuation, "the mix keeps paging: $page2")
        } finally {
            m.close()
        }
    }

    @Test
    fun playlist_detail_decodes_the_app_url_engine_side() = runTest {
        val (m, _) = manager { jsonResponse(MIX_NEXT_FIXTURE).build(it) }
        try {
            // A seeded Mix url: the engine reads its own `v=` round-trip state back out.
            m.extractPlaylistDetail("/playlist?list=RDEMtestmix&v=kXYiU_JCYtU")
            val mixSent = assertNotNull(sentBody)
            assertTrue(mixSent.contains(""""videoId":"kXYiU_JCYtU""""), mixSent)
            // A seed embedded in the Mix id itself (RD<videoId>) is derived engine-side.
            m.extractPlaylistDetail("/playlist?list=RDkXYiU_JCYtU")
            val derived = assertNotNull(sentBody)
            assertTrue(derived.contains(""""videoId":"kXYiU_JCYtU""""), derived)
            // A regular playlist browses as VL<id> — the prefix is the engine's business.
            m.extractPlaylistDetail("/playlist?list=PLBcAa442MLAqvWaER")
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"VLPLBcAa442MLAqvWaER""""), sent)
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
            assertFailsWith<EngineError.Network> { m.extractHomeFeed() }
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
