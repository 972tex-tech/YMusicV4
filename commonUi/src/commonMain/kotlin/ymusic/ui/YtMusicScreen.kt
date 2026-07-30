package ymusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.extractMusicBrowse
import ymusic.dynamicengine.yt.extractMusicExplore
import ymusic.dynamicengine.yt.extractMusicHome
import ymusic.ui.resources.Res
import ymusic.ui.resources.detail_playlist
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
enum class YtMusicTab(val label: StringResource) {
    Home(Res.string.ytmusic_tab_home),
    Explore(Res.string.ytmusic_tab_explore),
    Library(Res.string.ytmusic_tab_library),
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
    // Live swipe progress (−1..1) so the underline tracks the drag, not just the commit — same as
    // Discover / Library.
    var tabDrag by remember { mutableFloatStateOf(0f) }
    val openDrawer = LocalOpenNavDrawer.current
    Column(Modifier.fillMaxSize()) {
        // The tab row is pinned under the app bar and elevates with it (one continuous surface),
        // exactly like Library's — the shell app bar drops its own bottom shadow for this section.
        ElevatedBarColumn {
            YtMusicTabRow(tab, tabDrag) { tab = it }
            HorizontalDivider(color = DesignColors.Divider)
        }
        SwipeTabSwitcher(
            tab,
            YtMusicTab.entries,
            onSelect = { tab = it },
            onDragProgress = { tabDrag = it },
            leadingEdgeReserve = NavDrawerEdgeReserve,
            onLeadingEdgeSwipe = openDrawer,
        ) { current ->
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

/** The music tab row — the shared [UnderlineTabRow] (2dp sliding underline) with text labels, the
 *  same treatment as Library's. */
@Composable
private fun YtMusicTabRow(selected: YtMusicTab, dragProgress: Float, onSelect: (YtMusicTab) -> Unit) {
    UnderlineTabRow(selected, YtMusicTab.entries, dragProgress, onSelect) { t, active ->
        TabLabel(stringResource(t.label), active)
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
 * The music album/playlist detail — the SAME layout as the WEB [PlaylistDetailScreen]: back ·
 * "Playlist" · search app bar, then the [PlaylistHeader] (title · meta · actions · shuffle FAB)
 * scrolling above the feed. A song tap plays the page as the queue from that song; the FAB shuffles.
 * (Artists reuse [ChannelDetailScreen] instead — routed in App.kt.)
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
        DetailAppBar(stringResource(Res.string.detail_playlist), onBack, onSearch)
        FeedList(
            session.feed.pager,
            coordinator,
            onPlayItem = { tapped ->
                coordinator.playAll(videos, startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0))
            },
            topContent = {
                PlaylistHeader(
                    title = ready?.pageHeader?.title ?: session.item.title,
                    meta = ready?.pageHeader?.subtitleLine1 ?: session.item.subtitleLine1,
                    onShuffle = { coordinator.playAll(videos, shuffle = true) },
                )
            },
            onOpen = onOpen,
            listState = session.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}
