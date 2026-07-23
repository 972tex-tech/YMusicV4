package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtPageHeader
import ymusic.dynamicengine.yt.YtPageTab
import ymusic.dynamicengine.yt.extractChannelDetail
import ymusic.dynamicengine.yt.extractPlaylistDetail
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_add_to_playlist
import ymusic.ui.resources.cd_download
import ymusic.ui.resources.cd_share
import ymusic.ui.resources.cd_shuffle
import ymusic.ui.resources.detail_playlist
import ymusic.ui.resources.subscribe

/**
 * Playlist detail's retained state (feed + scroll), created when the page is pushed. [url] is the
 * engine-composed app link ("/playlist?…"), round-tripped VERBATIM — how it's served (VL browse,
 * Mix watch-panel, seed videos…) is entirely the engine's business. [item] only seeds the header
 * while the page loads.
 */
class PlaylistSession(graph: AppGraph, scope: CoroutineScope, val url: String, val item: YtFeedItem) {
    val feed = FeedSession(scope) { continuation ->
        graph.engines.extractPlaylistDetail(url, continuation = continuation, ctx = graph.ytContext)
    }

    init {
        feed.pager.refresh()
    }
}

/**
 * Playlist detail (design/final/playlist-detail.html): back · "Playlist" · search app bar, then the
 * header (title + meta + actions row + shuffle FAB) scrolling above the video feed. A video tap
 * plays the playlist as the queue from that video; the FAB shuffles it.
 */
@Composable
fun PlaylistDetailScreen(
    coordinator: PlaybackCoordinator,
    session: PlaylistSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val s = session
    val item = s.item
    val state by s.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(stringResource(Res.string.detail_playlist), onBack, onSearch)
        FeedList(
            s.feed.pager,
            coordinator,
            onPlayItem = { tapped ->
                coordinator.playAll(videos, startIndex = videos.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0))
            },
            topContent = {
                PlaylistHeader(
                    title = ready?.pageHeader?.title ?: item.title,
                    meta = ready?.pageHeader?.subtitleLine1 ?: item.subtitleLine1,
                    onShuffle = { coordinator.playAll(videos, shuffle = true) },
                )
            },
            listState = s.feed.listState,
        )
    }
}

/** Title + meta, the actions row (design chrome until playlists/share/downloads land) and the
 *  shuffle FAB, closed by the 16dp-inset divider. */
@Composable
private fun PlaylistHeader(title: String, meta: String?, onShuffle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Text(
                title,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.Ink, lineHeight = 25.sp),
            )
            meta?.let {
                Text(
                    it,
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(Res.string.cd_add_to_playlist), enabled = false, onClick = {})
            ActionIcon(Icons.Outlined.Share, stringResource(Res.string.cd_share), enabled = false, onClick = {})
            ActionIcon(Icons.Outlined.FileDownload, stringResource(Res.string.cd_download), enabled = false, onClick = {})
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(DesignColors.Ink).clickable(onClick = onShuffle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Shuffle, stringResource(Res.string.cd_shuffle), Modifier.size(20.dp), tint = Color.White)
            }
        }
        HorizontalDivider(color = DesignColors.StackNear, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * Channel detail's retained state (feed + scroll + the picked tab), created when pushed. [url] is
 * the engine-composed app link ("/channel/…", possibly pinned to a server tab), round-tripped
 * VERBATIM; a tab switch just swaps in that tab's own engine link and refetches. [item] only seeds
 * the header while the page loads.
 */
class ChannelSession(
    graph: AppGraph,
    scope: CoroutineScope,
    val item: YtFeedItem,
    initialUrl: String,
) {
    var url by mutableStateOf(initialUrl)

    /** The last non-empty tab row — survives a tab-switch reload (Loading has no page yet). */
    var tabs by mutableStateOf(listOf<YtPageTab>())
    val feed = FeedSession(scope) { continuation ->
        graph.engines.extractChannelDetail(url, continuation = continuation, ctx = graph.ytContext)
    }

    init {
        feed.pager.refresh()
    }
}

/**
 * Channel detail (design/final/channel-detail.html): back · name · search app bar, the pinned text
 * tab row (server tabs: Home / Videos / …), then banner + avatar header scrolling above the tab's
 * feed. Playlist taps open through [onOpen] (playlist detail); video taps autoplay as usual.
 */
@Composable
fun ChannelDetailScreen(
    coordinator: PlaybackCoordinator,
    session: ChannelSession,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val s = session
    val item = s.item
    val state by s.feed.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready

    LaunchedEffect(ready?.pageTabs) {
        ready?.pageTabs?.takeIf { it.isNotEmpty() }?.let { s.tabs = it }
    }

    Column(Modifier.fillMaxSize()) {
        DetailAppBar(ready?.pageHeader?.title ?: item.title, onBack, onSearch) {
            if (s.tabs.isNotEmpty()) {
                ChannelTabRow(s.tabs, activeUrl = s.url) { tab ->
                    tab.url?.let {
                        s.url = it
                        s.feed.pager.refresh()
                    }
                }
                HorizontalDivider(color = DesignColors.Divider)
            }
        }
        FeedList(
            s.feed.pager,
            coordinator,
            onOpen = onOpen,
            topContent = { ChannelHeader(ready?.pageHeader, fallback = item) },
            listState = s.feed.listState,
            onDeeplink = onDeeplink,
        )
    }
}

/** Banner strip (when the channel has one) + avatar · name/meta · the Subscribe pill (chrome until
 *  accounts land). */
@Composable
private fun ChannelHeader(header: YtPageHeader?, fallback: YtFeedItem) {
    Column(Modifier.fillMaxWidth()) {
        val banner = header?.banner.orEmpty()
        if (banner.isNotEmpty()) {
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DesignColors.Placeholder),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Image, null, Modifier.size(26.dp), tint = DesignColors.PlaceholderIcon)
                ThumbImage(banner, 480.dp, RoundedCornerShape(12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(DesignColors.PlaceholderDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Person, null, Modifier.size(26.dp), tint = Color(0xFFAAAAAA))
                ThumbImage(header?.thumbs ?: fallback.thumbs, 64.dp, CircleShape)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    header?.title ?: fallback.title,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = header?.subtitleLine2 ?: header?.subtitleLine1 ?: fallback.subtitleLine1
                meta?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // Real action lands with accounts; filled pill per the mockup's channel header.
            Text(
                stringResource(Res.string.subscribe),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(DesignColors.Ink)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}
