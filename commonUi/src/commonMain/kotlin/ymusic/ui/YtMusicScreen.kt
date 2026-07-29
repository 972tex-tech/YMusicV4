package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.extractMusicBrowse
import ymusic.dynamicengine.yt.extractMusicExplore
import ymusic.dynamicengine.yt.extractMusicHome
import ymusic.ui.resources.Res
import ymusic.ui.resources.ytmusic_explore_empty
import ymusic.ui.resources.ytmusic_home_empty
import ymusic.ui.resources.ytmusic_library_signin
import ymusic.ui.resources.ytmusic_tab_explore
import ymusic.ui.resources.ytmusic_tab_home
import ymusic.ui.resources.ytmusic_tab_library

/** The engine-composed music detail link prefix ("/ytmusic/browse?id=…", see musicParse.ts) — the
 *  app routes items/deeplinks carrying it to the music detail layer instead of the WEB pages. */
internal const val MusicBrowsePrefix = "/ytmusic/browse"

/** The YT Music (Beta) top tabs. Home/Explore are anonymous WEB_REMIX feeds; Library needs sign-in. */
enum class YtMusicTab(val label: StringResource, val icon: ImageVector) {
    Home(Res.string.ytmusic_tab_home, Icons.Outlined.Home),
    Explore(Res.string.ytmusic_tab_explore, Icons.Outlined.Explore),
    Library(Res.string.ytmusic_tab_library, Icons.Outlined.LibraryMusic),
}

/**
 * YT Music (Beta) — the music.youtube.com surface. Three swipeable tabs (Home / Explore / Library),
 * each Home/Explore backed by a cached [FeedSession] over the `ytmusic/…` engine commands and drawn
 * with the shared [FeedList]; Library is a sign-in stub for now. Songs play on tap (FeedList's
 * default); album/artist/playlist cards navigate through [onOpen] (an engine-composed
 * "/ytmusic/browse?…" link). The section's search lives in the shell app bar.
 *
 * [sessions] is hoisted from the app root (like Discover's) so switching tabs — or returning from a
 * search/detail layer — shows the loaded feed at its scroll position instead of reloading; it is
 * cleared when the content locale changes.
 */
@Composable
fun YtMusicScreen(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    sessions: MutableMap<YtMusicTab, FeedSession>,
    localeEpoch: Int,
    scope: CoroutineScope,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(YtMusicTab.Home) }
    Column(Modifier.fillMaxSize()) {
        YtMusicTabRow(tab) { tab = it }
        HorizontalDivider(color = DesignColors.Divider)
        SwipeTabSwitcher(tab, YtMusicTab.entries, onSelect = { tab = it }) { current ->
            if (current == YtMusicTab.Library) {
                CenteredBox {
                    Text(
                        stringResource(Res.string.ytmusic_library_signin),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val session = remember(current, localeEpoch) {
                    sessions.getOrPut(current) {
                        FeedSession(scope) { continuation ->
                            when (current) {
                                YtMusicTab.Explore -> graph.engines.extractMusicExplore(continuation)
                                else -> graph.engines.extractMusicHome(continuation)
                            }
                        }.also { it.pager.refresh() }
                    }
                }
                FeedList(
                    session.pager,
                    coordinator,
                    emptyMessage = stringResource(
                        if (current == YtMusicTab.Explore) Res.string.ytmusic_explore_empty else Res.string.ytmusic_home_empty,
                    ),
                    onOpen = onOpen,
                    listState = session.listState,
                    onDeeplink = onDeeplink,
                )
            }
        }
    }
}

/** The three music tabs as an icon+label row with the accent underline on the active one. */
@Composable
private fun YtMusicTabRow(selected: YtMusicTab, onSelect: (YtMusicTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        YtMusicTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(
                        t.icon,
                        null,
                        Modifier.size(18.dp),
                        tint = if (active) DesignColors.Accent else DesignColors.InkFaint,
                    )
                    Text(
                        stringResource(t.label),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) DesignColors.Accent else DesignColors.InkFaint,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * A music detail page (album / artist / playlist) served by `ytmusic/browse`. [url] is the
 * engine-composed "/ytmusic/browse?id=<browseId>" link, round-tripped VERBATIM; [item] seeds the
 * header while it loads.
 */
class MusicBrowseSession(graph: AppGraph, scope: CoroutineScope, val url: String, val item: YtFeedItem) {
    val feed = FeedSession(scope) { continuation ->
        graph.engines.extractMusicBrowse(url, continuation = continuation)
    }

    init {
        feed.pager.refresh()
    }
}

/**
 * The music detail surface: back · title · search app bar over the browse feed. A song tap plays the
 * page as the queue from that song; nested album/playlist/artist cards open through [onOpen].
 */
@Composable
fun MusicDetailScreen(
    coordinator: PlaybackCoordinator,
    session: MusicBrowseSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val state by session.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(ready?.pageHeader?.title ?: session.item.title, onBack, onSearch)
        FeedList(
            session.feed.pager,
            coordinator,
            onPlayItem = { tapped ->
                coordinator.playAll(videos, startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0))
            },
            onOpen = onOpen,
            listState = session.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}
