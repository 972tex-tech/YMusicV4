package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.YtSection
import io.ymusic.dynamicengine.yt.extractAccountLibrary
import io.ymusic.dynamicengine.yt.extractHomeFeed
import io.ymusic.dynamicengine.yt.extractMusicBrowse
import io.ymusic.dynamicengine.yt.extractTrendingMusicFeed
import io.ymusic.dynamicengine.yt.getRecommendedFeed
import io.ymusic.dynamicengine.yt.handleWebViewPlayNext
import io.ymusic.player.PlayableItem
import io.ymusic.player.VideoQualityState
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.coming_soon
import io.ymusic.ui.resources.discover_needs_account
import io.ymusic.ui.resources.discover_no_anonymous_home
import io.ymusic.ui.resources.discover_no_recommendations
import io.ymusic.ui.resources.discover_no_top_tracks
import io.ymusic.ui.resources.discover_no_trending
import io.ymusic.ui.resources.section_catalogue
import io.ymusic.ui.resources.section_discover
import io.ymusic.ui.resources.section_donate
import io.ymusic.ui.resources.section_downloads
import io.ymusic.ui.resources.section_facebook
import io.ymusic.ui.resources.section_feedback
import io.ymusic.ui.resources.section_library
import io.ymusic.ui.resources.section_rewind
import io.ymusic.ui.resources.section_settings
import io.ymusic.ui.resources.section_yt_music
import io.ymusic.ui.resources.tab_account_library
import io.ymusic.ui.resources.tab_home
import io.ymusic.ui.resources.tab_music
import io.ymusic.ui.resources.tab_recommended
import io.ymusic.ui.resources.tab_top_tracks
import io.ymusic.ui.sponsorblock.SponsorBlockCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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

/** Switch the top-level section from within a screen (e.g. Downloads → "Reveal in Library"); no-op
 *  outside the app shell. */
val LocalNavigateToSection = androidx.compose.runtime.compositionLocalOf<(AppSection) -> Unit> { {} }

/** A pending "open this folder in the Library" request (a bucket id), stashed by one screen and
 *  consumed by the Library once it composes — so Downloads' "Reveal in Library" lands on the file's
 *  actual folder, not wherever the Library was left. Null host outside the shell. */
val LocalLibraryFolderReveal =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<Long?>?> { null }

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

/** [sectionState] hoisted for tests and for shells that deep-link from platform surfaces (a
 *  download notification tap lands on Downloads): flipping it is exactly what a drawer selection
 *  does (on touch platforms the drawer edge-swipes open over ANY screen, which a headless jvm rig
 *  can't drive). */
@Composable
fun App(graph: AppGraph, sectionState: androidx.compose.runtime.MutableState<AppSection>) {
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    // ONE language, decided ONCE: the shell seeded the setting before the engine started
    // (ensureAppLanguage), so this is a concrete tag — the blank fallback only covers unseeded
    // test graphs. The engine pulls the same key for its hl default; no push happens here.
    val tag = uiLanguage.ifBlank { defaultUiLanguageTag() }
    remember(tag) { applyUiLocale(tag) }
    val themePref by remember(graph) { graph.settings.flow(AppSettings.theme) }.collectAsState()
    val dark = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    var section by sectionState
    LaunchedEffect(graph.player, graph.settings) {
        val quality = graph.settings.string(PlaybackQualitySettingKey, VideoQualityState.AUTO).value
        if (quality != VideoQualityState.AUDIO_ONLY) graph.player.selectVideoQuality(quality)
        graph.player.setVolume(graph.settings.string(PlaybackVolumeKey, "1").value.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)
        graph.player.setSpeed(graph.settings.string(PlaybackSpeedKey, "1").value.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f)
        graph.player.setPitch(graph.settings.string(PlaybackPitchKey, "1").value.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f)
        graph.settings.string(PlaybackEqKey, "").value.split(',').mapNotNull { it.toFloatOrNull() }.let { n ->
            if (n.size >= 12) {
                graph.player.setBassBoost(n[0])
                graph.player.setVirtualizer(n[1])
                graph.player.setEqBands(n.drop(2).take(10))
            }
        }
        launch {
            graph.player.config.map { it.preferVideoQuality.first }.distinctUntilChanged()
                .collect { graph.settings.putString(PlaybackQualitySettingKey, it) }
        }
        launch {
            graph.player.config.map { "${it.volume}|${it.speed}|${it.pitchFactor}|${it.bassBoostDb}|${it.virtualizer}|${it.eqBands.joinToString(",")}" }
                .distinctUntilChanged()
                .collect { blob ->
                    val p = blob.split('|')
                    if (p.size >= 6) {
                        graph.settings.putString(PlaybackVolumeKey, p[0])
                        graph.settings.putString(PlaybackSpeedKey, p[1])
                        graph.settings.putString(PlaybackPitchKey, p[2])
                        graph.settings.putString(PlaybackEqKey, "${p[3]},${p[4]},${p[5]}")
                    }
                }
        }
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

    /** A generic "/feed/…" page (account history / all playlists) — plain feed, no detail chrome. */
    class Feed(val session: FeedDetailSession) : AppLayer

    class MusicBrowse(val session: MusicBrowseSession) : AppLayer

    /** The YouTube sign-in browser. Holds its [LoginWebViewState] so the back gesture can walk the
     *  page's own history (canGoBack) before popping the layer. */
    /** [switchAccount]: the browser opens YouTube's channel switcher instead of sign-in — no cookie
     *  polling; CLOSING the layer is the completion signal (popLayer refreshes session+account). */
    class Login(val state: LoginWebViewState, val switchAccount: Boolean = false) : AppLayer
}

/** The layer's scope logger, the parent for anything opened on top of it (null = the root). */
private val AppLayer.log: kmpcommon.log.Logger?
    get() = when (this) {
        is AppLayer.Search -> session.log
        is AppLayer.Playlist -> session.log
        is AppLayer.Channel -> session.log
        is AppLayer.Feed -> session.log
        is AppLayer.MusicBrowse -> session.log
        is AppLayer.LibrarySearch, is AppLayer.Login -> null
    }

@Composable
private fun AppRoot(graph: AppGraph, section: AppSection, onSection: (AppSection) -> Unit) {
    run {
        val scope = rememberCoroutineScope()
        val coordinator = remember { PlaybackCoordinator(graph, scope) }
        remember { SponsorBlockCoordinator(graph, scope) }
        val inPip by graph.pip.inPip.collectAsState()
        if (inPip) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                VideoHole(graph.player, graph.playerSurface, Modifier.fillMaxSize())
            }
            return@run
        }
        // A folder-reveal target (bucket id) set by Downloads' "Reveal in Library" and consumed by the
        // Library once it composes. Lives here so it survives the section switch.
        val libraryReveal = remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
        // Signed-in state, derived from the engine's cookie gate. Bumps loginEpoch on sign-in/out.
        val accountController = remember { AccountController(graph, scope) }
        val signedIn by accountController.signedIn.collectAsState()
        val account by accountController.account.collectAsState()
        val profileReady by accountController.profileReady.collectAsState()
        val loginEpoch by accountController.loginEpoch.collectAsState()
        // The Recommended feed's raw material: the playing page reports YouTube's related items for
        // a music video and the engine keeps them. Pure hand-off — the app parses nothing, and a
        // harvest that fails must never disturb playback.
        LaunchedEffect(graph) {
            graph.player.watchNextSeen.collect { seen ->
                runCatching { graph.engines.handleWebViewPlayNext(seen.videoId, seen.contentsJson) }
                    .onFailure { graph.logger?.fork("watch-next-harvest")?.log(kmpcommon.log.Logger.Level.WARN, "harvest hand-off failed", it) }
            }
        }
        // App-level so a scan started in Library keeps running (and still notifies) across sections.
        val scanController = remember { ScanController(scope, graph.logger?.fork("library-scan")) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        if (!DEBUG_BUILD) {
            LaunchedEffect(section) {
                if (section == AppSection.Catalogue) onSection(AppSection.Discover)
            }
        }
        var discoverTab by rememberSaveable { mutableStateOf(DiscoverTab.Home) }
        // Live swipe progress (−1..1) so the Discover underline tracks the drag, not just the commit.
        var discoverDrag by remember { mutableFloatStateOf(0f) }
        val nowPlayingOpen by graph.pip.nowPlayingOpen.collectAsState()
        var nowPlayingPeeking by remember { mutableStateOf(false) }
        // Discover feeds cache per browseId — switching tab or navigating away and back must show
        // the already-loaded feed at its old scroll position, not reload it.
        val discoverSessions = remember { mutableMapOf<String, FeedSession>() }
        // YT Music's per-tab feed cache — same lifetime/locale rules as Discover's above.
        val ytMusicSessions = remember { mutableMapOf<YtMusicTab, FeedSession>() }
        // The Feedback section's draft — lives here so a half-written report survives a section
        // switch (the section's composable is disposed while another section shows).
        val feedbackSession = remember { FeedbackSession(graph.logger) }
        // The content locale decides what every feed CONTAINS and the language of its
        // server-composed text — but it lives in the ENGINE, so the app only hears "it changed"
        // ([AppGraph.localeEpoch], bumped by the Settings pickers) and throws the cached pages
        // away. It lives here, not in DiscoverFeed: that composable is disposed while a search or
        // detail layer is open, and a remember there would re-clear the cache on every return.
        val localeEpoch by graph.localeEpoch.collectAsState()
        // A sign-in/out changes what every feed CONTAINS (personalized vs anonymous), same as a
        // locale change — so it clears the caches too, and the feeds refetch under the new identity.
        remember(localeEpoch, loginEpoch) {
            discoverSessions.clear()
            ytMusicSessions.clear()
        }
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
        // The parent trail for a newly opened layer: the layer it was opened FROM, else the root.
        // Resolved per open (the stack top changes between opens), never captured once.
        fun parentLog() = layers.lastOrNull()?.log ?: graph.logger
        val openItem: (io.ymusic.dynamicengine.yt.YtFeedItem) -> Unit = { item ->
            val url = item.url
            if (url != null) {
                // Music album/artist/playlist cards carry an engine-composed "/ytmusic/browse?…" link
                // served by the WEB_REMIX music client — a different surface from the WEB
                // playlist/channel pages, so it routes to its own detail layer.
                if (url.startsWith(MusicBrowsePrefix)) {
                    // A music artist reuses the YouTube channel screen (no tab row — a music browse
                    // returns no pageTabs), fetching via ytmusic/browse; albums/playlists get the
                    // playlist-style music detail.
                    if (item.kind == io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL) {
                        layers.add(
                            AppLayer.Channel(
                                ChannelSession(graph, scope, item, initialUrl = url, parentLog = parentLog()) { u, c, l ->
                                    graph.engines.extractMusicBrowse(u, c, logger = l)
                                },
                            ),
                        )
                    } else {
                        layers.add(AppLayer.MusicBrowse(MusicBrowseSession(graph, scope, url, item, parentLog())))
                    }
                } else {
                    when (item.kind) {
                        io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST ->
                            layers.add(AppLayer.Playlist(PlaylistSession(graph, scope, url, item, parentLog())))
                        io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL ->
                            layers.add(AppLayer.Channel(ChannelSession(graph, scope, item, initialUrl = url, parentLog = parentLog())))
                    }
                }
            }
        }
        // Engine-composed deeplinks (a shelf's "View all"): the link decides the surface, the
        // header title seeds the page while it loads.
        val openDeeplink: (String, String?) -> Unit = { link, title ->
            val seed = { kind: String ->
                io.ymusic.dynamicengine.yt.YtFeedItem(kind = kind, id = link, title = title.orEmpty(), url = link)
            }
            if (link.startsWith(MusicBrowsePrefix)) {
                layers.add(
                    AppLayer.MusicBrowse(
                        MusicBrowseSession(graph, scope, link, seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST), parentLog()),
                    ),
                )
            } else when (val target = parseDeeplink(link)) {
                is Deeplink.Playlist -> layers.add(
                    AppLayer.Playlist(
                        PlaylistSession(graph, scope, target.url, seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_PLAYLIST), parentLog()),
                    ),
                )
                is Deeplink.Channel -> layers.add(
                    AppLayer.Channel(
                        ChannelSession(
                            graph,
                            scope,
                            seed(io.ymusic.dynamicengine.yt.YtFeedItem.KIND_CHANNEL),
                            initialUrl = target.url,
                            parentLog = parentLog(),
                        ),
                    ),
                )
                is Deeplink.Feed -> layers.add(
                    AppLayer.Feed(FeedDetailSession(graph, scope, target.url, title, parentLog())),
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
                else -> layers.add(AppLayer.Search(SearchSession.youtube(graph, scope)))
            }
        }
        // YT Music's search (its own stack): a music SearchSession over ytmusic/search, single-instance
        // the same way.
        val openMusicSearch: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Search }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Search(SearchSession.music(graph, scope)))
            }
        }
        val popLayer = {
            val top = layers.removeAt(layers.lastIndex)
            // Closing the account-switcher browser IS its completion (no cookie gate flips while
            // switching): refresh session + account + feeds under whatever identity was picked.
            if (top is AppLayer.Login && top.switchAccount) accountController.onLoginComplete()
        }
        // The YouTube sign-in browser — single-instance like search: reopening (from the Account tab
        // preface or a future gated action like Subscribe) raises the existing layer.
        val openLogin: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Login }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Login(LoginWebViewState()))
            }
        }
        // The account switcher — same single Login slot (login and switcher never stack).
        val openSwitchAccount: () -> Unit = {
            val existing = layers.indexOfFirst { it is AppLayer.Login }
            when {
                existing >= 0 && existing == layers.lastIndex -> Unit
                existing >= 0 -> layers.add(layers.removeAt(existing))
                else -> layers.add(AppLayer.Login(LoginWebViewState(), switchAccount = true))
            }
        }
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
                AppLayer.Search(SearchSession.youtube(graph, scope))
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
                nowPlayingOpen -> graph.pip.nowPlayingOpen.value = false
                top is AppLayer.Search && top.session.editing && top.session.submitted != null ->
                    top.session.editing = false
                top is AppLayer.LibrarySearch && top.session.drill != null -> top.session.drill = null
                // In the sign-in browser, back walks the page's own history first, then leaves.
                top is AppLayer.Login && top.state.canGoBack -> top.state.goBack()
                layers.isNotEmpty() -> popLayer()
                else -> onSection(AppSection.Discover)
            }
        }

        // The one shared video surface must be *realized* for a WebView (iOS YouTube) source to play
        // at all — a windowed WKWebView that is never shown never initializes, so it can't play even
        // audio-only. Now Playing hosts it while open; otherwise the mini player's art tile does, so
        // playback keeps going (the same single container just re-parents, which the engines tolerate).
        val queue by graph.player.queue.collectAsState()
        val status by graph.player.status.collectAsState()
        val needsSurface = queue.currentItem is PlayableItem.YtMedia || status.videoSize != null

        val holeOpen = desktopLayoutHole && nowPlayingOpen
        Box(
            Modifier.fillMaxSize()
                .then(if (desktopLayoutHole) Modifier else Modifier.background(DesignColors.Surface)),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
                // design/final/home.html: landscape puts the tab row inline in the app bar.
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp
                // Fully covering Now Playing is opaque: skip drawing the feed/mini-player under it
                // (4× GPU overdraw). Draw them again as soon as the sheet peeks so dismiss reveals.
                val drawShell = !nowPlayingOpen || nowPlayingPeeking
                Box(
                    Modifier.fillMaxSize().drawWithContent { if (drawShell) drawContent() },
                ) {
                    if (!holeOpen) ModalNavigationDrawer(
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
                        // Downloads pins its bar outright: no tab row below it, and with the grid's status
                        // sections a vanishing toolbar reads as lost context, not gained space.
                        val collapsible = platformUsesTouch && !wide && section != AppSection.Downloads
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
                        // Download-format picker: any video menu sets this to a video; DownloadDialogHost shows
                        // the format dialog and enqueues into graph.downloads. Null launcher when no service.
                        var pendingDownload by remember { mutableStateOf<io.ymusic.dynamicengine.yt.YtFeedItem?>(null) }
                        val downloadLauncher = remember(graph) {
                            graph.downloads?.let { DownloadLauncher { video -> pendingDownload = video } }
                        }
                        CompositionLocalProvider(
                            LocalScrollHost provides scrollHost,
                            LocalSnackbar provides showSnackbar,
                            LocalScanController provides scanController,
                            LocalOpenNavDrawer provides { scope.launch { drawer.open() } },
                            LocalDownloadLauncher provides downloadLauncher,
                            LocalNavigateToSection provides { onSection(it) },
                            LocalLibraryFolderReveal provides libraryReveal,
                        ) {
                            DownloadDialogHost(graph, pendingDownload) { pendingDownload = null }
                            DefaultDownloadLocationPrompt(graph)
                            Scaffold(
                                modifier = Modifier.nestedScroll(collapseConnection),
                                containerColor = Color.Transparent,
                                snackbarHost = { SnackbarHost(snackbarHostState) },
                                topBar = {
                                    // Search and the detail pages carry their own app bars.
                                    if (layers.isEmpty()) {
                                        // Library's tab row lives in its own body (it needs the loaded state),
                                        // so the shell bar drops its shadow there and lets that row cast it —
                                        // keeping bar + tabs one continuous elevated surface.
                                        ElevatedBarColumn(bottomShadow = section != AppSection.Library && section != AppSection.YtMusic) {
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
                                                        section == AppSection.YtMusic -> openMusicSearch
                                                        section == AppSection.Library && graph.mediaStore != null -> openLibrarySearch
                                                        else -> null
                                                    },
                                                    // Inline tabs sit right after the title, so the slot
                                                    // reserves the widest tab label — the tabs must not
                                                    // shift when switching tabs re-titles the bar.
                                                    titleReserveWidthOf = if (section == AppSection.Discover && wide) {
                                                        DiscoverTab.entries.map { stringResource(it.label) }
                                                    } else {
                                                        emptyList()
                                                    },
                                                    inlineContent = if (section == AppSection.Discover && wide) {
                                                        { DiscoverTabRow(discoverTab, inline = true, dragProgress = discoverDrag) { discoverTab = it } }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            }
                                            if (section == AppSection.Discover && !wide) {
                                                DiscoverTabRow(discoverTab, dragProgress = discoverDrag) { discoverTab = it }
                                                HorizontalDivider(color = DesignColors.Divider)
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                val screens = @Composable {
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
                                                // A music artist page's search is music search, not YouTube.
                                                onSearch = if (top.session.url.startsWith(MusicBrowsePrefix)) openMusicSearch else openSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.Feed -> key(top) {
                                            FeedDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                onSearch = openSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.MusicBrowse -> key(top) {
                                            MusicDetailScreen(
                                                coordinator,
                                                top.session,
                                                onBack = popLayer,
                                                onSearch = openMusicSearch,
                                                onOpen = openItem,
                                                onDeeplink = openDeeplink,
                                            )
                                        }
                                        is AppLayer.Login -> key(top) {
                                            YtLoginScreen(
                                                graph,
                                                top.state,
                                                onBack = popLayer,
                                                // Gate passed: drop the browser, then let the controller pull
                                                // the account card, reload feeds, and stale the player page.
                                                // (The switcher never fires this — popLayer is its signal.)
                                                onComplete = {
                                                    popLayer()
                                                    accountController.onLoginComplete()
                                                },
                                                switchAccount = top.switchAccount,
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
                                                        localeEpoch,
                                                        loginEpoch,
                                                        discoverSessions,
                                                        scope,
                                                        signedIn = signedIn,
                                                        profileReady = profileReady,
                                                        account = account,
                                                        onLogin = openLogin,
                                                        onSignOut = { accountController.signOut() },
                                                        onSwitchAccount = openSwitchAccount,
                                                        onOpen = openItem,
                                                        onDeeplink = openDeeplink,
                                                    )
                                                }
                                                AppSection.YtMusic -> YtMusicScreen(
                                                    graph,
                                                    coordinator,
                                                    ytMusicSessions,
                                                    localeEpoch + loginEpoch,
                                                    scope,
                                                    signedIn = signedIn,
                                                    onLogin = openLogin,
                                                    onOpen = openItem,
                                                    onDeeplink = openDeeplink,
                                                )
                                                AppSection.Library -> LibraryScreen(graph, coordinator)
                                                AppSection.Downloads -> DownloadsScreen(graph, coordinator)
                                                AppSection.Settings -> SettingsScreen(
                                                    graph,
                                                    signedIn = signedIn,
                                                    onLogin = openLogin,
                                                )
                                                AppSection.Feedback ->
                                                    if (graph.sendFeedback != null) FeedbackScreen(graph, feedbackSession)
                                                    else ComingSoon(section)
                                                AppSection.Catalogue ->
                                                    if (DEBUG_BUILD) CatalogueScreen() else ComingSoon(section)
                                                else -> ComingSoon(section)
                                            }
                                        }
                                    }
                                }
                                val mini: @Composable (Modifier) -> Unit = { mod ->
                                    MiniPlayer(
                                        coordinator,
                                        surface = if (needsSurface && !nowPlayingOpen) graph.playerSurface else null,
                                        onExpand = { graph.pip.nowPlayingOpen.value = true },
                                        modifier = mod,
                                    )
                                }
                                if (desktopLayoutHole) {
                                    Column(Modifier.fillMaxSize()) {
                                        Column(
                                            Modifier.weight(1f).fillMaxWidth().padding(padding)
                                                .background(DesignColors.Surface),
                                        ) { screens() }
                                        mini(Modifier.fillMaxWidth())
                                    }
                                } else {
                                    Box(Modifier.fillMaxSize()) {
                                        Column(Modifier.fillMaxSize().padding(padding)) { screens() }
                                        mini(
                                            Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .navigationBarsPadding(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                UpdateDialog(graph)
            }
            if (nowPlayingOpen) {
                NowPlayingScreen(
                    graph,
                    coordinator,
                    onClose = {
                        graph.pip.nowPlayingOpen.value = false
                        nowPlayingPeeking = false
                    },
                    onPeek = { nowPlayingPeeking = it > 0.5f },
                )
            }
        }
    }
}

/** Discover's five feed tabs (design §1). Home and Music have anonymous backing feeds;
 *  Recommended and Top tracks are built from local play; Account is the signed-in library. */
enum class DiscoverTab(val label: StringResource, val icon: ImageVector, val hasFeed: Boolean) {
    Home(Res.string.tab_home, Icons.Outlined.Home, hasFeed = true),
    Music(Res.string.tab_music, Icons.Outlined.MusicNote, hasFeed = true),
    Recommended(Res.string.tab_recommended, Icons.Default.StarBorder, hasFeed = true),
    TopTracks(Res.string.tab_top_tracks, Icons.AutoMirrored.Outlined.TrendingUp, hasFeed = true),
    Account(Res.string.tab_account_library, Icons.Outlined.Person, hasFeed = false),
}

/** Icon tabs with the 2dp underline indicator; [inline] is the app-bar form (tighter gap, no leading
 *  inset). A single indicator slides between tabs (and leans toward the neighbour by [dragProgress],
 *  −1..1, from the content swipe) instead of the underline snapping tab-to-tab. */
@Composable
internal fun DiscoverTabRow(
    selected: DiscoverTab,
    inline: Boolean = false,
    dragProgress: Float = 0f,
    onSelect: (DiscoverTab) -> Unit,
) {
    val density = LocalDensity.current
    // Each tab's centre (in the row's space) so the fixed-width indicator can slide between them.
    val centers = remember { mutableStateMapOf<DiscoverTab, Dp>() }
    // The tab's padding is INSIDE its clickable, so press/hover fills a square around the icon
    // instead of hugging the glyph's own width. The row's spacing and leading inset are reduced by
    // that padding, which keeps the icons drawn exactly where they were.
    Column(Modifier.padding(start = if (inline) 0.dp else 20.dp - TabTouchPadding)) {
        Row(horizontalArrangement = Arrangement.spacedBy((if (inline) 20.dp else 24.dp) - TabTouchPadding * 2)) {
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

private val DiscoverUnderlineWidth = 34.dp

/** Breathing room around a Discover tab icon — the press/hover surface, not a visual gap. */
private val TabTouchPadding = 7.dp

@Composable
private fun DiscoverFeed(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    tab: DiscoverTab,
    localeEpoch: Int,
    loginEpoch: Int,
    sessions: MutableMap<String, FeedSession>,
    scope: kotlinx.coroutines.CoroutineScope,
    signedIn: Boolean,
    profileReady: Boolean,
    account: io.ymusic.dynamicengine.yt.YtAccountInfo?,
    onLogin: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpen: ((io.ymusic.dynamicengine.yt.YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    if (tab == DiscoverTab.Account) {
        // The user/library tab: spinner until the profile probe finishes, then the sign-in
        // preface when logged out or the account library when in.
        if (!profileReady) {
            CenteredBox { CircularProgressIndicator() }
        } else if (signedIn) {
            // Cached like the other tabs (cleared with them on a locale/login epoch change).
            val session = remember(tab, localeEpoch, loginEpoch) {
                sessions.getOrPut(tab.name) {
                    val log = graph.logger?.fork("AccountLibrary")
                    FeedSession(scope, log) { continuation ->
                        graph.engines.extractAccountLibrary(continuation = continuation, logger = log)
                    }.also { it.pager.refresh() }
                }
            }
            AccountLibraryScreen(
                session,
                coordinator,
                account,
                onSignOut = onSignOut,
                onSwitchAccount = onSwitchAccount,
                onOpen = onOpen,
                onDeeplink = onDeeplink,
            )
        } else {
            LoginPreface(onLogin)
        }
    } else if (!tab.hasFeed) {
        CenteredBox {
            Text(
                stringResource(Res.string.discover_needs_account, stringResource(tab.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Recommended and Top tracks cost no network and grow as the user plays, so they rebuild
        // on every visit on a scope that dies with the tab instead of freezing the first read.
        val localScope = rememberCoroutineScope()
        val localFeed = tab == DiscoverTab.Recommended || tab == DiscoverTab.TopTracks
        // Cached per tab (and on the app scope): coming back to the tab — or back from
        // search / a detail page — shows the feed as it was, no reload. Re-keyed on loginEpoch so a
        // sign-in/out rebuilds the feed under the new (personalized/anonymous) identity.
        val session = remember(tab, localeEpoch, loginEpoch) {
            if (localFeed) {
                FeedSession(localScope, graph.logger?.fork(tab.name)) {
                    when (tab) {
                        DiscoverTab.TopTracks -> YtFeedPage(
                            sections = listOf(
                                YtSection(items = graph.playHistory?.youtubeTopChart().orEmpty()),
                            ),
                        )
                        else -> graph.engines.getRecommendedFeed()
                    }
                }.also { it.pager.refresh() }
            } else {
                sessions.getOrPut(tab.name) {
                    val log = graph.logger?.fork(tab.name)
                    FeedSession(scope, log) { continuation ->
                        when (tab) {
                            // The region's music charts — one page, no continuation ever arrives.
                            DiscoverTab.Music -> graph.engines.extractTrendingMusicFeed(logger = log)
                            else -> {
                                val page = graph.engines.extractHomeFeed(continuation = continuation, logger = log)
                                // Home is a music app's front door: music first (per section —
                                // reordering across sections would tear headers off their items).
                                page.copy(sections = page.sections.map { it.copy(items = rankMusicFirst(it.items)) })
                            }
                        }
                    }.also { it.pager.refresh() }
                }
            }
        }
        val removeFromTopTracks: ((YtFeedItem) -> Unit)? =
            if (tab == DiscoverTab.TopTracks) {
                { item ->
                    localScope.launch {
                        graph.playHistory?.deleteYoutube(item.id)
                        session.pager.reload()
                    }
                }
            } else {
                null
            }
        CompositionLocalProvider(LocalRemoveFromTopTracks provides removeFromTopTracks) {
            FeedList(
                session.pager,
                coordinator,
                emptyMessage = stringResource(
                    when (tab) {
                        DiscoverTab.Music -> Res.string.discover_no_trending
                        DiscoverTab.Recommended -> Res.string.discover_no_recommendations
                        DiscoverTab.TopTracks -> Res.string.discover_no_top_tracks
                        else -> Res.string.discover_no_anonymous_home
                    },
                ),
                onOpen = onOpen,
                onPlayItem = if (tab == DiscoverTab.TopTracks) {
                    { tapped ->
                        val videos = (session.pager.state.value as? Pager.State.Ready)
                            ?.sections.orEmpty()
                            .flatMap { it.items }
                            .filter { it.kind == YtFeedItem.KIND_VIDEO }
                        coordinator.playAll(
                            videos,
                            startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0),
                        )
                    }
                } else {
                    null
                },
                listState = session.listState,
                onDeeplink = onDeeplink,
            )
        }
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
