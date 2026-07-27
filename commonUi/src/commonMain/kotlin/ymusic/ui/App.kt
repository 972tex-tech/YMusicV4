package ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.applyYtLocale
import ymusic.dynamicengine.yt.extractHomeFeed
import ymusic.dynamicengine.yt.extractTrendingMusicFeed
import ymusic.dynamicengine.yt.getRecommendedFeed
import ymusic.dynamicengine.yt.handleWebViewPlayNext
import ymusic.dynamicengine.yt.ytLocale
import ymusic.player.PlayableItem
import ymusic.player.VideoQualityState
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.settings.put
import ymusic.ui.resources.Res
import ymusic.ui.resources.coming_soon
import ymusic.ui.resources.discover_needs_account
import ymusic.ui.resources.discover_no_anonymous_home
import ymusic.ui.resources.discover_no_recommendations
import ymusic.ui.resources.discover_no_trending
import ymusic.ui.resources.section_catalogue
import ymusic.ui.resources.section_discover
import ymusic.ui.resources.section_donate
import ymusic.ui.resources.section_downloads
import ymusic.ui.resources.section_facebook
import ymusic.ui.resources.section_feedback
import ymusic.ui.resources.section_library
import ymusic.ui.resources.section_rewind
import ymusic.ui.resources.section_settings
import ymusic.ui.resources.section_yt_music
import ymusic.ui.resources.tab_account_library
import ymusic.ui.resources.tab_home
import ymusic.ui.resources.tab_music
import ymusic.ui.resources.tab_recommended
import ymusic.ui.resources.tab_top_tracks
import kotlin.math.abs
import kotlin.math.roundToInt

/** App-level sections — the drawer's three groups (design/final/drawer.html). */
enum class AppSection(val label: StringResource, val icon: ImageVector, val group: Int) {
    Discover(Res.string.section_discover, Icons.Outlined.OndemandVideo, 0),
    YtMusic(Res.string.section_yt_music, Icons.Default.PlayCircleOutline, 0),
    Library(Res.string.section_library, Icons.Outlined.LibraryMusic, 0),
    Rewind(Res.string.section_rewind, Icons.Outlined.FastRewind, 1),
    Downloads(Res.string.section_downloads, Icons.Outlined.FileDownload, 1),
    Settings(Res.string.section_settings, Icons.Outlined.Settings, 1),
    Catalogue(Res.string.section_catalogue, Icons.Outlined.Palette, 1),
    Donate(Res.string.section_donate, Icons.Outlined.FavoriteBorder, 2),
    Facebook(Res.string.section_facebook, Icons.Default.Facebook, 2),
    Feedback(Res.string.section_feedback, Icons.AutoMirrored.Outlined.Chat, 2),
}

/**
 * The app root: drawer navigation + the persistent mini-player + the Now Playing overlay. The shell
 * calls this with a ready [AppGraph] (engines started, player built, surface injected).
 *
 * The UI-language setting is applied here: the default locale is overridden and the whole tree is
 * rebuilt under `key(tag)` so every `stringResource` re-resolves, with the layout direction derived
 * from the effective locale (RTL scripts flip the UI). [section] lives above the `key` so a
 * language switch doesn't navigate away from Settings.
 */
@Composable
fun App(graph: AppGraph) {
    val section = rememberSaveable { mutableStateOf(AppSection.Discover) }
    App(graph, section)
}

/** [sectionState] hoisted for tests: flipping it is exactly what a drawer selection does (on touch
 *  platforms the drawer edge-swipes open over ANY screen, which a headless jvm rig can't drive). */
@Composable
internal fun App(graph: AppGraph, sectionState: androidx.compose.runtime.MutableState<AppSection>) {
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    val tag = uiLanguage.ifBlank { null }
    remember(tag) { applyUiLocale(tag) }
    val themePref by remember(graph) { graph.settings.flow(AppSettings.theme) }.collectAsState()
    val dark = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    var section by sectionState
    // Keep the shared cookie identity in step with the locale pick (PREF hl/gl — the WebView
    // player follows cookies, not per-request context), then ask YouTube where it thinks this
    // connection is. Unchanged settings make the apply a free no-op, so the start-up run only
    // seeds the detected default; a real change re-scrapes under the new identity, and reverting
    // to automatic restores the IP-derived region on the spot.
    val contentHl by remember(graph) { graph.settings.flow(AppSettings.hl) }.collectAsState()
    val contentGl by remember(graph) { graph.settings.flow(AppSettings.gl) }.collectAsState()
    LaunchedEffect(graph.engines, graph.settings, contentHl, contentGl) {
        runCatching { graph.engines.applyYtLocale(contentHl, contentGl) }
        runCatching { graph.engines.ytLocale() }
            .onSuccess { if (it.gl.isNotBlank()) graph.settings.put(AppSettings.glDetected, it.gl) }
    }
    LaunchedEffect(graph.player, graph.settings) {
        val quality = graph.settings.string(PlaybackQualitySettingKey, VideoQualityState.AUDIO_ONLY).value
        graph.player.selectVideoQuality(quality)
        graph.player.config
            .map { it.preferVideoQuality.first }
            .distinctUntilChanged()
            .collect { graph.settings.putString(PlaybackQualitySettingKey, it) }
    }
    key(tag) {
        val direction = if (isRtlLanguage(Locale.current.language)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            YMusicTheme(dark = dark) {
                CompositionLocalProvider(
                    LocalImageLoader provides graph.imageLoader,
                    LocalRefreshRequests provides graph.refreshRequests,
                ) {
                    AppRoot(graph, section, onSection = { section = it })
                }
            }
        }
    }
}

/** App-wide reload requests (desktop F5/Cmd+R) — the composed feed surface collects and refetches.
 *  Null outside the app shell. */
internal val LocalRefreshRequests = compositionLocalOf<kotlinx.coroutines.flow.Flow<Unit>?> { null }

/** Show a transient message in the app's snackbar. No-op outside the app shell (tests, standalone). */
internal val LocalSnackbar = compositionLocalOf<(String) -> Unit> { {} }

/**
 * One pushed navigation layer. Each layer OWNS its screen state (loaded feed, scroll position,
 * query…) so lower layers survive intact under pushed ones — back always lands where the user
 * left, e.g. search results stay loaded while a result's detail page is open above them.
 */
private sealed interface AppLayer {
    class Search(val session: SearchSession) : AppLayer

    class LibrarySearch(val session: LibrarySearchSession) : AppLayer

    class Playlist(val session: PlaylistSession) : AppLayer

    class Channel(val session: ChannelSession) : AppLayer
}

@Composable
private fun AppRoot(graph: AppGraph, section: AppSection, onSection: (AppSection) -> Unit) {
    run {
        val scope = rememberCoroutineScope()
        val coordinator = remember { PlaybackCoordinator(graph, scope) }
        // The Recommended feed's raw material: the playing page reports YouTube's related items for
        // a music video and the engine keeps them. Pure hand-off — the app parses nothing, and a
        // harvest that fails must never disturb playback.
        LaunchedEffect(graph) {
            graph.player.watchNextSeen.collect { seen ->
                runCatching { graph.engines.handleWebViewPlayNext(seen.videoId, seen.contentsJson) }
            }
        }
        // App-level so a scan started in Library keeps running (and still notifies) across sections.
        val scanController = remember { ScanController(scope) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var discoverTab by rememberSaveable { mutableStateOf(DiscoverTab.Home) }
        // Live swipe progress (−1..1) so the Discover underline tracks the drag, not just the commit.
        var discoverDrag by remember { mutableFloatStateOf(0f) }
        var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
        // Discover feeds cache per browseId — switching tab or navigating away and back must show
        // the already-loaded feed at its old scroll position, not reload it.
        val discoverSessions = remember { mutableMapOf<String, FeedSession>() }
        // hl/gl decide what every feed CONTAINS and the language of its server-composed text, so a
        // picker change throws the cached pages away instead of only affecting the next fetch. It
        // lives here, not in DiscoverFeed: that composable is disposed while a search or detail
        // layer is open, and a remember there would re-clear the cache on every return.
        val ytContext = rememberYtContext(graph)
        remember(ytContext) { discoverSessions.clear() }
        // Each section retains its OWN layer stack: the drawer switches sections laterally — no
        // push, no clear — so Discover's search/results/detail pages survive a trip through
        // Library and are exactly where the user left them on return. The stacks never mix:
        // AppLayer.Search is YouTube search and only ever lives in Discover's stack (Library's
        // future search is a different, local-files flow).
        val sectionLayers = remember { mutableMapOf<AppSection, androidx.compose.runtime.snapshots.SnapshotStateList<AppLayer>>() }
        val layers = sectionLayers.getOrPut(section) { mutableStateListOf() }
        val sectionStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        // Navigation is url-driven: every openable target carries an engine-composed app link
        // (item.url / tab.url / a shelf CTA's deeplink) that the app routes on and round-trips
        // verbatim — the tapped item itself only seeds the header while the page loads.
        val openItem: (ymusic.dynamicengine.yt.YtFeedItem) -> Unit = { item ->
            val url = item.url
            if (url != null) {
                when (item.kind) {
                    ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST ->
                        layers.add(AppLayer.Playlist(PlaylistSession(graph, scope, url, item)))
                    ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL ->
                        layers.add(AppLayer.Channel(ChannelSession(graph, scope, item, initialUrl = url)))
                }
            }
        }
        // Engine-composed deeplinks (a shelf's "View all"): the link decides the surface, the
        // header title seeds the page while it loads.
        val openDeeplink: (String, String?) -> Unit = { link, title ->
            val seed = { kind: String ->
                ymusic.dynamicengine.yt.YtFeedItem(kind = kind, id = link, title = title.orEmpty(), url = link)
            }
            when (val target = parseDeeplink(link)) {
                is Deeplink.Playlist -> layers.add(
                    AppLayer.Playlist(
                        PlaylistSession(graph, scope, target.url, seed(ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST)),
                    ),
                )
                is Deeplink.Channel -> layers.add(
                    AppLayer.Channel(
                        ChannelSession(
                            graph,
                            scope,
                            seed(ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL),
                            initialUrl = target.url,
                        ),
                    ),
                )
                null -> Unit // an older app meeting a newer bundle's scheme — the CTA is inert
            }
        }
        // Search is single-instance (Android's SINGLE_TOP + BRING_TO_FRONT): reopening search from
        // anywhere raises the existing layer — with its query, results and scroll — to the top
        // instead of stacking a fresh one.
        val openSearch: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Search }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit // already on top
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Search(SearchSession(graph, scope)))
            }
        }
        val popLayer = { layers.removeAt(layers.lastIndex); Unit }
        // Library's own search flow (local files, filter-as-you-type) — a different surface from
        // YouTube search, single-instance within Library's stack the same way.
        val openLibrarySearch: () -> Unit = {
            val store = graph.mediaStore
            if (store != null) {
                val existing = layers.indexOfFirst { it is AppLayer.LibrarySearch }
                when {
                    existing >= 0 && existing == layers.lastIndex -> Unit
                    existing >= 0 -> layers.add(layers.removeAt(existing))
                    else -> layers.add(AppLayer.LibrarySearch(LibrarySearchSession(store, scope)))
                }
            }
        }
        // The "Search "q" online" hand-off: jump to Discover and run q as a YouTube search there,
        // reusing Discover's single search layer (its stack, not Library's).
        val searchOnline: (String) -> Unit = { q ->
            onSection(AppSection.Discover)
            val discover = sectionLayers.getOrPut(AppSection.Discover) { mutableStateListOf() }
            val existing = discover.indexOfFirst { it is AppLayer.Search }
            val layer = if (existing >= 0) {
                discover.removeAt(existing) as AppLayer.Search
            } else {
                AppLayer.Search(SearchSession(graph, scope))
            }
            layer.session.apply {
                field = androidx.compose.ui.text.input.TextFieldValue(q)
                submitted = q
                editing = false
                filterParams = null
                pager.refresh()
            }
            discover.add(layer)
        }

        // The system back gesture/button walks the same path as the in-app back arrows: overlays
        // close first (drawer, Now Playing); the search input steps back to its results; then one
        // layer of the CURRENT section's stack pops. A lateral section with nothing left to unwind
        // steps back to Discover — which restores its own retained stack — and only from Discover's
        // root is back left to the OS to exit the app.
        PlatformBackHandler(
            enabled = drawer.isOpen || nowPlayingOpen || layers.isNotEmpty() || section != AppSection.Discover,
        ) {
            val top = layers.lastOrNull()
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                nowPlayingOpen -> nowPlayingOpen = false
                top is AppLayer.Search && top.session.editing && top.session.submitted != null ->
                    top.session.editing = false
                top is AppLayer.LibrarySearch && top.session.drill != null -> top.session.drill = null
                layers.isNotEmpty() -> popLayer()
                else -> onSection(AppSection.Discover)
            }
        }

        // The one shared video surface must be *realized* for a WebView (YouTube) source to play at
        // all — a windowed JCEF/WKWebView that is never shown never initializes, so it can't play even
        // audio-only. Now Playing hosts it while open; otherwise the mini player's art tile does, so
        // playback keeps going (the same single container just re-parents, which the engines tolerate).
        val queue by graph.player.queue.collectAsState()
        val status by graph.player.status.collectAsState()
        val needsSurface = queue.currentItem is PlayableItem.YtVideoId || status.videoSize != null

        BoxWithConstraints(Modifier.fillMaxSize().background(DesignColors.Surface).safeDrawingPadding()) {
            // design/final/home.html: landscape puts the tab row inline in the app bar.
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            ModalNavigationDrawer(
                drawerState = drawer,
                // Swipe-open is touch-only: with a mouse, the whole-screen drag would claim any
                // click whose press wanders a pixel (mouse slop ~0.1dp). Open stays possible while
                // showing so scrim/drag dismiss keeps working on desktop. On the swipe-tab screens the
                // tab switcher yields a start-edge strip (leadingEdgeReserve) so this still opens there.
                gesturesEnabled = platformUsesTouch || drawer.isOpen,
                drawerContent = {
                    AppDrawer(
                        section = section,
                        appVersion = graph.appVersion,
                        onSelect = { s ->
                            onSection(s)
                            scope.launch { drawer.close() }
                        },
                    )
                },
            ) {
                // The shell owns app-bar elevation: content reports its scroll via TrackScroll into
                // this host, and the bar reads it. (Content never touches the bar directly.)
                val scrollHost = remember { ScrollHost() }
                // Touch platforms scroll the toolbar row away (enter-always) while the tab row
                // stays pinned; with the tabs inline (wide) the bar carries them, so it stays put.
                val collapsible = platformUsesTouch && !wide
                var toolbarHeightPx by remember { mutableStateOf(0) }
                var toolbarOffsetPx by remember { mutableStateOf(0f) }
                LaunchedEffect(section, discoverTab, layers.isEmpty(), collapsible) {
                    toolbarOffsetPx = 0f
                }
                val collapseConnection = remember(collapsible) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (!collapsible) return Offset.Zero
                            val previous = toolbarOffsetPx
                            toolbarOffsetPx = (previous + available.y).coerceIn(-toolbarHeightPx.toFloat(), 0f)
                            return Offset(0f, toolbarOffsetPx - previous)
                        }
                    }
                }
                val snackbarHostState = remember { SnackbarHostState() }
                val showSnackbar: (String) -> Unit = { message ->
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(message)
                    }
                }
                // Scan results are notification-class: show them on whatever screen is open.
                LaunchedEffect(scanController) {
                    scanController.summaries.collect { showSnackbar(it) }
                }
                CompositionLocalProvider(
                    LocalScrollHost provides scrollHost,
                    LocalSnackbar provides showSnackbar,
                    LocalScanController provides scanController,
                    LocalOpenNavDrawer provides { scope.launch { drawer.open() } },
                ) {
                    Scaffold(
                        modifier = Modifier.nestedScroll(collapseConnection),
                        containerColor = DesignColors.Surface,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            // Search and the detail pages carry their own app bars.
                            if (layers.isEmpty()) {
                                // Library's tab row lives in its own body (it needs the loaded state),
                                // so the shell bar drops its shadow there and lets that row cast it —
                                // keeping bar + tabs one continuous elevated surface.
                                ElevatedBarColumn(bottomShadow = section != AppSection.Library) {
                                    Box(
                                        Modifier.clipToBounds().layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            toolbarHeightPx = placeable.height
                                            val height = (placeable.height + toolbarOffsetPx).roundToInt().coerceAtLeast(0)
                                            layout(placeable.width, height) {
                                                placeable.place(0, toolbarOffsetPx.roundToInt())
                                            }
                                        },
                                    ) {
                                        DesignAppBar(
                                            title = stringResource(
                                                if (section == AppSection.Discover) discoverTab.label else section.label,
                                            ),
                                            onMenu = { scope.launch { drawer.open() } },
                                            // Each section's own search flow: YouTube on Discover,
                                            // local files on Library, none elsewhere.
                                            onSearch = when {
                                                section == AppSection.Discover -> openSearch
                                                section == AppSection.Library && graph.mediaStore != null -> openLibrarySearch
                                                else -> null
                                            },
                                            inlineContent = if (section == AppSection.Discover && wide) {
                                                { DiscoverTabRow(discoverTab, underline = false) { discoverTab = it } }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                    if (section == AppSection.Discover && !wide) {
                                        DiscoverTabRow(discoverTab, underline = true, dragProgress = discoverDrag) { discoverTab = it }
                                        HorizontalDivider(color = DesignColors.Divider)
                                    }
                                }
                            }
                        },
                        bottomBar = {
                            Column {
                                // While Now Playing is closed, the mini player's art tile hosts the
                                // video surface (all platforms — the tile lays it out at real size
                                // and scales the drawing, see MiniPlayer) so a WebView source keeps
                                // playing across the transition.
                                MiniPlayer(
                                    coordinator,
                                    surface = if (needsSurface && !nowPlayingOpen) graph.playerSurface else null,
                                    onExpand = { nowPlayingOpen = true },
                                )
                            }
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            when (val top = layers.lastOrNull()) {
                                is AppLayer.Search -> key(top) {
                                    SearchScreen(
                                        graph,
                                        coordinator,
                                        onBack = popLayer,
                                        onOpen = openItem,
                                        session = top.session,
                                        onDeeplink = openDeeplink,
                                    )
                                }
                                is AppLayer.LibrarySearch -> key(top) {
                                    LibrarySearchScreen(
                                        top.session,
                                        coordinator,
                                        onBack = popLayer,
                                        onSearchOnline = searchOnline,
                                    )
                                }
                                is AppLayer.Playlist -> key(top) {
                                    PlaylistDetailScreen(
                                        coordinator,
                                        top.session,
                                        onBack = popLayer,
                                        onSearch = openSearch,
                                    )
                                }
                                is AppLayer.Channel -> key(top) {
                                    ChannelDetailScreen(
                                        coordinator,
                                        top.session,
                                        onBack = popLayer,
                                        onSearch = openSearch,
                                        onOpen = openItem,
                                        onDeeplink = openDeeplink,
                                    )
                                }
                                // Saveable state (tab picks, folder crumbs, scroll positions) is
                                // kept per section while its content is out of composition —
                                // Library must come back on the tab and folder it was left on.
                                null -> sectionStateHolder.SaveableStateProvider(section.name) {
                                    when (section) {
                                        AppSection.Discover -> SwipeTabSwitcher(
                                            discoverTab,
                                            DiscoverTab.entries,
                                            onSelect = { discoverTab = it },
                                            onDragProgress = { discoverDrag = it },
                                            leadingEdgeReserve = NavDrawerEdgeReserve,
                                            onLeadingEdgeSwipe = { scope.launch { drawer.open() } },
                                        ) { current ->
                                            DiscoverFeed(
                                                graph,
                                                coordinator,
                                                current,
                                                ytContext,
                                                discoverSessions,
                                                scope,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        AppSection.Library -> LibraryScreen(graph, coordinator)
                                        AppSection.Settings -> SettingsScreen(graph)
                                        AppSection.Catalogue -> CatalogueScreen()
                                        else -> ComingSoon(section)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (nowPlayingOpen) {
                NowPlayingScreen(graph, coordinator, onClose = { nowPlayingOpen = false })
            }
        }
    }
}

/** Discover's five feed tabs (design §1). Home and Music have anonymous backing feeds; the rest
 *  light up with accounts (they mirror the signed-in YouTube surfaces). */
enum class DiscoverTab(val label: StringResource, val icon: ImageVector, val hasFeed: Boolean) {
    Home(Res.string.tab_home, Icons.Outlined.Home, hasFeed = true),
    Music(Res.string.tab_music, Icons.Outlined.MusicNote, hasFeed = true),
    Recommended(Res.string.tab_recommended, Icons.Default.StarBorder, hasFeed = true),
    TopTracks(Res.string.tab_top_tracks, Icons.AutoMirrored.Outlined.TrendingUp, hasFeed = false),
    Account(Res.string.tab_account_library, Icons.Outlined.Person, hasFeed = false),
}

/** Icon tabs with the 2dp underline indicator; [underline] off for the inline (app bar) form. A single
 *  indicator slides between tabs (and leans toward the neighbour by [dragProgress], −1..1, from the
 *  content swipe) instead of the underline snapping tab-to-tab. */
@Composable
internal fun DiscoverTabRow(
    selected: DiscoverTab,
    underline: Boolean,
    dragProgress: Float = 0f,
    onSelect: (DiscoverTab) -> Unit,
) {
    val density = LocalDensity.current
    // Each tab's centre (in the row's space) so the fixed-width indicator can slide between them.
    val centers = remember { mutableStateMapOf<DiscoverTab, Dp>() }
    // The tab's padding is INSIDE its clickable, so press/hover fills a square around the icon
    // instead of hugging the glyph's own width. The row's spacing and leading inset are reduced by
    // that padding, which keeps the icons drawn exactly where they were.
    Column(Modifier.padding(start = if (underline) 20.dp - TabTouchPadding else 0.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy((if (underline) 24.dp else 20.dp) - TabTouchPadding * 2)) {
            DiscoverTab.entries.forEach { t ->
                val active = t == selected
                Box(
                    Modifier.onGloballyPositioned { c ->
                        val x = c.parentLayoutCoordinates?.localPositionOf(c)?.x ?: 0f
                        centers[t] = with(density) { (x + c.size.width / 2f).toDp() }
                    }
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(t) }
                        .padding(TabTouchPadding),
                ) {
                    Icon(
                        t.icon,
                        stringResource(t.label),
                        Modifier.size(22.dp),
                        tint = if (active) DesignColors.Accent else DesignColors.InkFaint,
                    )
                }
            }
        }
        if (underline) {
            val cur = centers[selected]
            val i = DiscoverTab.entries.indexOf(selected)
            val neighbor = centers[DiscoverTab.entries[(i + if (dragProgress > 0f) 1 else -1).coerceIn(0, DiscoverTab.entries.lastIndex)]]
            val frac = abs(dragProgress) * TabIndicatorDragTravel
            val targetCenter = if (cur != null && neighbor != null) lerp(cur, neighbor, frac) else cur ?: 0.dp
            val center by animateDpAsState(targetCenter, label = "discoverIndicator")
            Box(
                Modifier
                    .offset(x = center - DiscoverUnderlineWidth / 2)
                    .width(DiscoverUnderlineWidth)
                    .height(2.dp)
                    .background(DesignColors.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val DiscoverUnderlineWidth = 34.dp

/** Breathing room around a Discover tab icon — the press/hover surface, not a visual gap. */
private val TabTouchPadding = 7.dp

@Composable
private fun DiscoverFeed(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    tab: DiscoverTab,
    ctx: ymusic.dynamicengine.yt.YtContext,
    sessions: MutableMap<String, FeedSession>,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpen: ((ymusic.dynamicengine.yt.YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    if (!tab.hasFeed) {
        CenteredBox {
            Text(
                stringResource(Res.string.discover_needs_account, stringResource(tab.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Recommended is the exception to the session cache below: it costs no network, it grows as
        // the user plays, and it reshuffles per read — so it is rebuilt on every visit, on a scope
        // that dies with the tab, instead of being frozen at whatever the first visit loaded.
        val localScope = rememberCoroutineScope()
        val recommended = tab == DiscoverTab.Recommended
        // Cached per tab (and on the app scope): coming back to the tab — or back from
        // search / a detail page — shows the feed as it was, no reload.
        val session = remember(tab, ctx) {
            if (recommended) {
                FeedSession(localScope) { graph.engines.getRecommendedFeed() }.also { it.pager.refresh() }
            } else {
                sessions.getOrPut(tab.name) {
                    FeedSession(scope) { continuation ->
                        when (tab) {
                            // The region's music charts — one page, no continuation ever arrives.
                            DiscoverTab.Music -> graph.engines.extractTrendingMusicFeed(ctx = ctx)
                            else -> {
                                val page = graph.engines.extractHomeFeed(
                                    continuation = continuation,
                                    ctx = ctx,
                                )
                                // Home is a music app's front door: music first (per section —
                                // reordering across sections would tear headers off their items).
                                page.copy(sections = page.sections.map { it.copy(items = rankMusicFirst(it.items)) })
                            }
                        }
                    }.also { it.pager.refresh() }
                }
            }
        }
        FeedList(
            session.pager,
            coordinator,
            emptyMessage = stringResource(
                when (tab) {
                    DiscoverTab.Music -> Res.string.discover_no_trending
                    DiscoverTab.Recommended -> Res.string.discover_no_recommendations
                    else -> Res.string.discover_no_anonymous_home
                },
            ),
            onOpen = onOpen,
            listState = session.listState,
            onDeeplink = onDeeplink,
        )
    }
}

@Composable
private fun ComingSoon(section: AppSection) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(Res.string.coming_soon, stringResource(section.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
