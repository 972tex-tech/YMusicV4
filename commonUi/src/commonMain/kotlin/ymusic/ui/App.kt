package ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.browse
import ymusic.player.PlayableItem
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_menu
import ymusic.ui.resources.cd_search
import ymusic.ui.resources.coming_soon
import ymusic.ui.resources.discover_needs_account
import ymusic.ui.resources.discover_no_anonymous_home
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
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    val tag = uiLanguage.ifBlank { null }
    remember(tag) { applyUiLocale(tag) }
    var section by rememberSaveable { mutableStateOf(AppSection.Discover) }
    key(tag) {
        val direction = if (isRtlLanguage(Locale.current.language)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            YMusicTheme {
                CompositionLocalProvider(LocalImageLoader provides graph.imageLoader) {
                    AppRoot(graph, section, onSection = { section = it })
                }
            }
        }
    }
}

/** Set true by the active screen while its content is scrolled off the top — the app bar lifts.
 *  Null outside the app shell (e.g. the standalone catalogue window). */
internal val LocalAppBarElevated = compositionLocalOf<MutableState<Boolean>?> { null }

@Composable
private fun AppRoot(graph: AppGraph, section: AppSection, onSection: (AppSection) -> Unit) {
    run {
        val scope = rememberCoroutineScope()
        val coordinator = remember { PlaybackCoordinator(graph, scope) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var discoverTab by rememberSaveable { mutableStateOf(DiscoverTab.Home) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

        // The one shared video surface must be *realized* for a WebView (YouTube) source to play at
        // all — a windowed JCEF/WKWebView that is never shown never initializes, so it can't play even
        // audio-only. Now Playing hosts it while open; otherwise the mini player's art tile does, so
        // playback keeps going (the same single container just re-parents, which the engines tolerate).
        val queue by graph.player.queue.collectAsState()
        val status by graph.player.status.collectAsState()
        val needsSurface = queue.currentItem is PlayableItem.YtVideoId || status.videoSize != null

        BoxWithConstraints {
            // design/final/home.html: landscape puts the tab row inline in the app bar.
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            ModalNavigationDrawer(
                drawerState = drawer,
                // Swipe-open is touch-only: with a mouse, the whole-screen drag would claim any
                // click whose press wanders a pixel (mouse slop ~0.1dp). Open stays possible while
                // showing so scrim/drag dismiss keeps working on desktop.
                gesturesEnabled = platformUsesTouch || drawer.isOpen,
                drawerContent = {
                    AppDrawer(
                        section = section,
                        appVersion = graph.appVersion,
                        onSelect = { s ->
                            onSection(s)
                            searching = false
                            scope.launch { drawer.close() }
                        },
                    )
                },
            ) {
                val barElevated = remember { mutableStateOf(false) }
                LaunchedEffect(section, discoverTab, searching) { barElevated.value = false }
                val barShadow by animateDpAsState(if (barElevated.value) 4.dp else 0.dp)
                CompositionLocalProvider(LocalAppBarElevated provides barElevated) {
                    Scaffold(
                        containerColor = Color.White,
                        topBar = {
                            if (!searching) {
                                Column(Modifier.statusBarsPadding().shadow(barShadow).background(Color.White)) {
                                    DesignAppBar(
                                        title = stringResource(
                                            if (section == AppSection.Discover) discoverTab.label else section.label,
                                        ),
                                        onMenu = { scope.launch { drawer.open() } },
                                        onSearch = { searching = true },
                                        inlineContent = if (section == AppSection.Discover && wide) {
                                            { DiscoverTabRow(discoverTab, underline = false) { discoverTab = it } }
                                        } else {
                                            null
                                        },
                                    )
                                    if (section == AppSection.Discover && !wide) {
                                        DiscoverTabRow(discoverTab, underline = true) { discoverTab = it }
                                        HorizontalDivider(color = DesignColors.Divider)
                                    }
                                }
                            }
                        },
                        bottomBar = {
                            Column {
                                // While Now Playing is closed the surface must stay hosted somewhere for
                                // a WebView source to keep playing. Where depends on the platform: a
                                // scale-tolerant surface (desktop OSR) becomes the mini player's art
                                // tile; a windowed WebView (Android) keeps a real-size docked preview —
                                // thumbnail-sized, the page's stream negotiation kills playback.
                                val hostSurface = needsSurface && !nowPlayingOpen
                                if (hostSurface && !graph.surfaceScalesDown) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.width(240.dp).aspectRatio(16f / 9f)) {
                                            graph.playerSurface(Modifier.fillMaxSize())
                                        }
                                    }
                                }
                                MiniPlayer(
                                    coordinator,
                                    surface = if (hostSurface && graph.surfaceScalesDown) graph.playerSurface else null,
                                    onExpand = { nowPlayingOpen = true },
                                )
                            }
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            when {
                                searching -> SearchScreen(graph, coordinator, onBack = { searching = false })
                                section == AppSection.Discover -> DiscoverFeed(graph, coordinator, discoverTab)
                                section == AppSection.Library -> LibraryScreen(graph, coordinator)
                                section == AppSection.Settings -> SettingsScreen(graph)
                                section == AppSection.Catalogue -> CatalogueScreen()
                                else -> ComingSoon(section)
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

/** The app bar (design/final/home.html): menu · title · [inline tabs on landscape] · search. */
@Composable
private fun DesignAppBar(
    title: String,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    inlineContent: (@Composable () -> Unit)? = null,
) {
    Row(
        // Paddings/gaps shave the ActionIcon hit-inset (5dp) so the glyphs keep the bar's metrics.
        // The mockup's 5px top margin sits under a status bar; a bare window edge needs its own.
        Modifier.fillMaxWidth().background(Color.White).padding(start = 11.dp, end = 11.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Default.Menu, stringResource(Res.string.cd_menu), onClick = onMenu)
        Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        if (inlineContent != null) {
            Box(Modifier.padding(start = 16.dp)) { inlineContent() }
        }
        Box(Modifier.weight(1f))
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
}

/** Discover's five feed tabs (design §1). Only Home has an anonymous backing feed today; the rest
 *  light up with accounts (they mirror the signed-in YouTube surfaces). */
enum class DiscoverTab(val label: StringResource, val icon: ImageVector, val browseId: String?) {
    Home(Res.string.tab_home, Icons.Outlined.Home, "FEwhat_to_watch"),
    Music(Res.string.tab_music, Icons.Outlined.MusicNote, null),
    Recommended(Res.string.tab_recommended, Icons.Default.StarBorder, null),
    TopTracks(Res.string.tab_top_tracks, Icons.AutoMirrored.Outlined.TrendingUp, null),
    Account(Res.string.tab_account_library, Icons.Outlined.Person, null),
}

/** Icon tabs with the 2dp underline indicator; [underline] off for the inline (app bar) form. */
@Composable
internal fun DiscoverTabRow(selected: DiscoverTab, underline: Boolean, onSelect: (DiscoverTab) -> Unit) {
    Row(
        Modifier.padding(start = if (underline) 20.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (underline) 24.dp else 20.dp),
    ) {
        DiscoverTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier.clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    t.icon,
                    stringResource(t.label),
                    Modifier.padding(top = 7.dp, bottom = 7.dp).size(22.dp),
                    tint = if (active) DesignColors.Accent else DesignColors.InkFaint,
                )
                Box(
                    Modifier
                        .width(34.dp)
                        .height(2.dp)
                        .background(
                            if (active && underline) DesignColors.Accent else Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun DiscoverFeed(graph: AppGraph, coordinator: PlaybackCoordinator, tab: DiscoverTab) {
    val scope = rememberCoroutineScope()
    val browseId = tab.browseId
    if (browseId == null) {
        CenteredBox {
            Text(
                stringResource(Res.string.discover_needs_account, stringResource(tab.label)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val pager = remember(browseId) {
            Pager(scope) { continuation ->
                graph.engines.browse(
                    browseId = if (continuation == null) browseId else null,
                    continuation = continuation,
                    ctx = graph.ytContext,
                )
            }.also { it.refresh() }
        }
        FeedList(
            pager,
            coordinator,
            emptyMessage = stringResource(Res.string.discover_no_anonymous_home),
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
