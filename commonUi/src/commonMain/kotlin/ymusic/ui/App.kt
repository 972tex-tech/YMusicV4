package ymusic.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.browse
import ymusic.player.PlayableItem

/** App-level sections — the drawer's three groups (design/final/drawer.html). */
enum class AppSection(val label: String, val icon: ImageVector, val group: Int) {
    Discover("Discover", Icons.Outlined.OndemandVideo, 0),
    YtMusic("YT Music (Beta)", Icons.Default.PlayCircleOutline, 0),
    Library("Library", Icons.Outlined.LibraryMusic, 0),
    Rewind("Rewind playlist", Icons.Outlined.FastRewind, 1),
    Downloads("Download manager", Icons.Outlined.FileDownload, 1),
    Settings("Settings", Icons.Outlined.Settings, 1),
    Catalogue("UI catalogue", Icons.Outlined.Palette, 1),
    Donate("Donate", Icons.Outlined.FavoriteBorder, 2),
    Facebook("Like us on Facebook", Icons.Default.Facebook, 2),
    Feedback("Feedback", Icons.AutoMirrored.Outlined.Chat, 2),
}

/**
 * The app root: drawer navigation + the persistent mini-player + the Now Playing overlay. The shell
 * calls this with a ready [AppGraph] (engines started, player built, surface injected).
 */
@Composable
fun App(graph: AppGraph) {
    YMusicTheme {
        CompositionLocalProvider(LocalImageLoader provides graph.imageLoader) {
            AppRoot(graph)
        }
    }
}

@Composable
private fun AppRoot(graph: AppGraph) {
    run {
        val scope = rememberCoroutineScope()
        val coordinator = remember { PlaybackCoordinator(graph, scope) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var section by rememberSaveable { mutableStateOf(AppSection.Discover) }
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
                            section = s
                            searching = false
                            scope.launch { drawer.close() }
                        },
                    )
                },
            ) {
                Scaffold(
                    containerColor = Color.White,
                    topBar = {
                        if (!searching) {
                            Column(Modifier.statusBarsPadding()) {
                                DesignAppBar(
                                    title = if (section == AppSection.Discover) discoverTab.label else section.label,
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
                            section == AppSection.Catalogue -> CatalogueScreen()
                            else -> ComingSoon(section)
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
        Modifier.fillMaxWidth().background(Color.White).padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Menu, "Menu", Modifier.size(22.dp).clickable(onClick = onMenu), tint = DesignColors.InkMuted)
        Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        if (inlineContent != null) {
            Box(Modifier.padding(start = 16.dp)) { inlineContent() }
        }
        Box(Modifier.weight(1f))
        Icon(Icons.Default.Search, "Search", Modifier.size(22.dp).clickable(onClick = onSearch), tint = DesignColors.InkMuted)
    }
}

/** Discover's five feed tabs (design §1). Only Home has an anonymous backing feed today; the rest
 *  light up with accounts (they mirror the signed-in YouTube surfaces). */
enum class DiscoverTab(val label: String, val icon: ImageVector, val browseId: String?) {
    Home("Home", Icons.Outlined.Home, "FEwhat_to_watch"),
    Music("Music", Icons.Outlined.MusicNote, null),
    Recommended("Recommended", Icons.Default.StarBorder, null),
    TopTracks("Top tracks", Icons.AutoMirrored.Outlined.TrendingUp, null),
    Account("Library", Icons.Outlined.Person, null),
}

/** Icon tabs with the 2dp underline indicator; [underline] off for the inline (app bar) form. */
@Composable
private fun DiscoverTabRow(selected: DiscoverTab, underline: Boolean, onSelect: (DiscoverTab) -> Unit) {
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
                    t.label,
                    Modifier.padding(top = 7.dp, bottom = 7.dp).size(22.dp),
                    tint = if (active) DesignColors.Ink else DesignColors.InkFaint,
                )
                Box(
                    Modifier
                        .width(34.dp)
                        .height(2.dp)
                        .background(
                            if (active && underline) DesignColors.Ink else Color.Transparent,
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
                "${tab.label} mirrors your YouTube account — coming with sign-in",
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
            emptyMessage = "YouTube serves no anonymous home feed — use Search, or sign in (coming)",
        )
    }
}

@Composable
private fun ComingSoon(section: AppSection) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(section.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${section.label} — coming in a later milestone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
