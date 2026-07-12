package ymusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.browse

/** App-level sections (the drawer, design §7). */
enum class AppSection(val label: String, val icon: ImageVector) {
    Discover("Discover", Icons.Default.Explore),
    YtMusic("YT Music (Beta)", Icons.Default.MusicNote),
    Library("Library", Icons.Default.LibraryMusic),
    Downloads("Download manager", Icons.Default.Download),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * The app root: drawer navigation + the persistent mini-player + the Now Playing overlay. The shell
 * calls this with a ready [AppGraph] (engines started, player built, surface injected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(graph: AppGraph) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val scope = rememberCoroutineScope()
        val coordinator = remember { PlaybackCoordinator(graph, scope) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var section by rememberSaveable { mutableStateOf(AppSection.Discover) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Text("YMusic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    AppSection.entries.forEach { s ->
                        NavigationDrawerItem(
                            label = { Text(s.label) },
                            icon = { Icon(s.icon, null) },
                            selected = s == section,
                            onClick = {
                                section = s
                                searching = false
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    if (!searching) {
                        TopAppBar(
                            title = { Text(section.label) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                            },
                            actions = {
                                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                            },
                        )
                    }
                },
                bottomBar = { MiniPlayer(coordinator) { nowPlayingOpen = true } },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        searching -> SearchScreen(graph, coordinator, onBack = { searching = false })
                        section == AppSection.Discover -> DiscoverScreen(graph, coordinator)
                        else -> ComingSoon(section)
                    }
                }
            }
        }

        if (nowPlayingOpen) {
            NowPlayingScreen(graph, coordinator, onClose = { nowPlayingOpen = false })
        }
    }
}

/** Discover's five feed tabs (design §1). Only Home has an anonymous backing feed today; the rest
 *  light up with accounts (they mirror the signed-in YouTube surfaces). */
private enum class DiscoverTab(val label: String, val icon: ImageVector, val browseId: String?) {
    Home("Home", Icons.Default.Home, "FEwhat_to_watch"),
    Music("Music", Icons.Default.MusicNote, null),
    Recommended("Recommended", Icons.Default.Star, null),
    TopTracks("Top tracks", Icons.Default.TrendingUp, null),
    Account("Library", Icons.Default.Person, null),
}

@Composable
private fun DiscoverScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(DiscoverTab.Home) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            DiscoverTab.entries.forEach { t ->
                Tab(selected = t == tab, onClick = { tab = t }, icon = { Icon(t.icon, t.label) })
            }
        }
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
}

@Composable
private fun ComingSoon(section: AppSection) {
    CenteredBox {
        Column {
            Icon(
                section.icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${section.label} — coming in a later milestone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
