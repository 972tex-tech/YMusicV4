package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpcommon.error.CodedError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtChip
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtSection
import ymusic.ui.resources.Res
import ymusic.ui.resources.error_generic
import ymusic.ui.resources.feed_empty
import ymusic.ui.resources.queue_last
import ymusic.ui.resources.queue_next
import ymusic.ui.resources.reload
import ymusic.ui.resources.retry
import ymusic.ui.resources.show_less
import ymusic.ui.resources.show_more
import ymusic.ui.resources.show_more_n

/** Paged feed loading over any yt page source (browse / search / watch-next continuations).
 *  [fetchTimeoutMs] is the watchdog: a wedged engine/HTTP call (seen live: Android CookieManager
 *  deadlocking against WebView browser-process startup) becomes a Failed page with Retry instead
 *  of an eternal spinner. */
class Pager(
    private val scope: CoroutineScope,
    private val fetchTimeoutMs: Long = 45_000,
    private val fetch: suspend (continuation: String?) -> ymusic.dynamicengine.yt.YtFeedPage,
) {
    sealed interface State {
        data object Loading : State

        data class Failed(val code: String, val detail: String?) : State

        data class Ready(
            val sections: List<YtSection>,
            val chips: List<YtChip>,
            val selectedChipToken: String?,
            val continuation: String?,
            val loadingMore: Boolean,
            /** Pull-to-refresh in flight: the list stays on screen, only the pull indicator spins. */
            val refreshing: Boolean = false,
            /** Search surfaces only: the server's filter submenu (empty elsewhere). */
            val filterGroups: List<ymusic.dynamicengine.yt.YtFilterGroup> = emptyList(),
            /** Detail surfaces only (channel / playlist browse). */
            val pageHeader: ymusic.dynamicengine.yt.YtPageHeader? = null,
            val pageTabs: List<ymusic.dynamicengine.yt.YtPageTab> = emptyList(),
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Error codes from a keep-content [reload] (the list stays put) — the UI shows a snackbar. */
    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: kotlinx.coroutines.flow.SharedFlow<String> = _errors.asSharedFlow()

    private var job: Job? = null

    /** (Re)load from the top; [chipToken] re-requests the feed filtered by that chip. */
    fun refresh(chipToken: String? = null) {
        job?.cancel()
        // A CHIP-CLICKED page often carries no chip row/filters of its own — keep the ones we came
        // from. A plain refresh is a new surface (a channel tab switch): stale chips must NOT leak
        // into it, or a Playlists tab shows the Videos tab's sort chips and replays their tokens.
        val carryOver = chipToken != null
        val previousChips = if (carryOver) (_state.value as? State.Ready)?.chips.orEmpty() else emptyList()
        val previousFilters = if (carryOver) (_state.value as? State.Ready)?.filterGroups.orEmpty() else emptyList()
        _state.value = State.Loading
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { fetch(chipToken) }
                readyFrom(page, chipToken, previousChips, previousFilters)
            } catch (e: TimeoutCancellationException) {
                State.Failed(TIMEOUT_CODE, "fetch timed out after ${fetchTimeoutMs}ms")
            } catch (e: CancellationException) {
                throw e // a newer refresh took over the state — this coroutine just dies
            } catch (e: Exception) {
                State.Failed((e as? CodedError)?.code ?: "E20030", e.message)
            }
        }
    }

    /** Keep-content reload (pull-to-refresh AND chip taps): the visible list stays on screen with
     *  only the pull indicator spinning, then the fresh page swaps in when it lands. A failed or
     *  timed-out reload keeps the old data and emits an error code ([errors], shown as a snackbar)
     *  instead of dropping the user onto a full-screen error page. [chipToken] filters by that chip;
     *  null reloads the surface's default top-of-feed. */
    fun reload(chipToken: String? = null) {
        val current = _state.value as? State.Ready ?: return refresh(chipToken)
        if (current.refreshing) return
        job?.cancel()
        val carryOver = chipToken != null
        val previousChips = if (carryOver) current.chips else emptyList()
        val previousFilters = if (carryOver) current.filterGroups else emptyList()
        _state.value = current.copy(refreshing = true)
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { fetch(chipToken) }
                readyFrom(page, chipToken, previousChips, previousFilters)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                _errors.tryEmit(TIMEOUT_CODE)
                current.copy(refreshing = false)
            } catch (e: Exception) {
                _errors.tryEmit((e as? CodedError)?.code ?: "E20030")
                current.copy(refreshing = false)
            }
        }
    }

    private fun readyFrom(
        page: ymusic.dynamicengine.yt.YtFeedPage,
        chipToken: String?,
        previousChips: List<YtChip>,
        previousFilters: List<ymusic.dynamicengine.yt.YtFilterGroup>,
    ): State.Ready {
        // Selection can't ride on token identity across reloads: a chip-filtered page that DOES
        // return its own row returns fresh tokens (the clicked one matches nothing), with the
        // applied chip server-marked selected instead. So trust a fresh row's own marks; only when
        // the row lacks them (carried-over channel sort chips) re-mark it by the clicked token.
        val rawChips = page.chips.ifEmpty { previousChips }
        val chips = when {
            page.chips.any { it.selected } -> page.chips
            chipToken != null -> rawChips.map { it.copy(selected = it.token == chipToken) }
            else -> rawChips
        }
        return State.Ready(
            sections = page.sections,
            chips = chips,
            selectedChipToken = chips.firstOrNull { it.selected }?.token ?: chipToken,
            continuation = page.continuation,
            loadingMore = false,
            filterGroups = page.filterGroups.ifEmpty { previousFilters },
            pageHeader = page.pageHeader,
            pageTabs = page.pageTabs,
        )
    }

    fun loadMore() {
        val current = _state.value as? State.Ready ?: return
        val token = current.continuation ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        job = scope.launch {
            _state.value = try {
                val page = withTimeout(fetchTimeoutMs) { fetch(token) }
                current.copy(
                    sections = appendSections(current.sections, page.sections),
                    continuation = page.continuation,
                    loadingMore = false,
                )
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                current.copy(loadingMore = false) // timed out — the tail button stays for a retry
            } catch (e: Exception) {
                // Keep what we have; the tail button stays for a retry.
                current.copy(loadingMore = false)
            }
        }
    }

    companion object {
        /** The watchdog's error code — the retry page for a hung (not failed) fetch. */
        const val TIMEOUT_CODE = "E20031"
    }

    private fun appendSections(current: List<YtSection>, more: List<YtSection>): List<YtSection> {
        // A continuation usually keeps feeding the run it left off in — a header-less incoming
        // section continues a header-less tail section rather than starting a visual break.
        val last = current.lastOrNull()
        val first = more.firstOrNull()
        if (last == null || first == null || last.header != null || first.header != null) return current + more
        return current.dropLast(1) + last.copy(items = last.items + first.items) + more.drop(1)
    }
}

/**
 * A feed surface's retained state — the loaded pages plus the scroll position. Hoist one of these
 * above the navigation switch (the app's layer stack, the Discover tab cache) so leaving and
 * returning to the surface shows it exactly as it was instead of reloading from the top.
 */
class FeedSession(scope: CoroutineScope, fetch: suspend (continuation: String?) -> ymusic.dynamicengine.yt.YtFeedPage) {
    val pager = Pager(scope, fetch = fetch)
    val listState = LazyListState()
}

/**
 * One feed surface (design/final/feed-example.html): filter chips + sections, each rendered as a
 * list on a phone-portrait width or as a 2/3-column grid on tablet/landscape widths. Item tap /
 * queue actions come from [coordinator]; [onOpen] navigates a tapped playlist/channel item (null =
 * those items aren't tappable); [onPlayItem] overrides the default video tap (playlist context
 * plays within the whole list instead of an autoplay queue). [topContent] renders above the
 * sections and scrolls with them (a detail page's header).
 */
@Composable
fun FeedList(
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    emptyMessage: String = stringResource(Res.string.feed_empty),
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    listState: LazyListState? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    val state by pager.state.collectAsState()
    // Pull-to-refresh / F5 reloads the surface from the top: drop any chip filter and take the
    // server's default first response, keeping the current list on screen until the new page lands.
    val reload = { pager.reload() }
    // Desktop F5/Cmd+R: only composed surfaces collect, so the request reaches the visible feed.
    val refreshRequests = LocalRefreshRequests.current
    if (refreshRequests != null) {
        LaunchedEffect(refreshRequests, pager) { refreshRequests.collect { reload() } }
    }
    // A keep-content reload that fails (pull-to-refresh, chip tap) keeps the old list and surfaces
    // the error as a snackbar instead of blanking the screen.
    val showSnackbar = LocalSnackbar.current
    var errorCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pager) { pager.errors.collect { errorCode = it } }
    errorCode?.let { code ->
        val message = stringResource(Res.string.error_generic, code)
        LaunchedEffect(code, message) {
            showSnackbar(message)
            errorCode = null
        }
    }
    val refreshing = (state as? Pager.State.Ready)?.refreshing == true
    PullToRefreshFeed(refreshing = refreshing, onRefresh = reload) {
        when (val s = state) {
            is Pager.State.Loading -> Column(modifier) {
                topContent?.invoke()
                CenteredBox { CircularProgressIndicator() }
            }
            is Pager.State.Failed -> Column(modifier) {
                topContent?.invoke()
                ErrorState(s.code) { pager.refresh() }
            }
            is Pager.State.Ready -> {
                if (s.sections.all { it.items.isEmpty() }) {
                    Column(modifier) {
                        topContent?.invoke()
                        FeedChips(s, pager)
                        EmptyState(emptyMessage) { pager.refresh(s.selectedChipToken) }
                    }
                } else {
                    FeedSections(s, pager, coordinator, modifier, onOpen, onPlayItem, topContent, listState, onDeeplink)
                }
            }
        }
    }
}

/** Touch platforms wrap the feed in Material's pull-to-refresh; desktop reloads via F5/Cmd+R
 *  instead (a mouse can't over-drag a list), so the wrapper is a no-op there. The indicator spins
 *  for any keep-content reload — a pull gesture or a chip tap — but not the first full-screen load
 *  (that state has no list to pull against and shows its own centered spinner). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefreshFeed(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    if (!platformUsesTouch) {
        content()
        return
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        content()
    }
}

/** Full-surface empty page: picture + message + an emphasized reload action. */
@Composable
internal fun EmptyState(message: String, modifier: Modifier = Modifier, onReload: () -> Unit) {
    CenteredBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Inbox,
                null,
                Modifier.size(56.dp).padding(bottom = 4.dp),
                tint = DesignColors.PlaceholderIcon,
            )
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onReload, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(Res.string.reload))
            }
        }
    }
}

/** Full-surface error page: picture + "Something went wrong (code)" + an emphasized retry action. */
@Composable
internal fun ErrorState(code: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    CenteredBox(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.CloudOff,
                null,
                Modifier.size(56.dp).padding(bottom = 4.dp),
                tint = DesignColors.PlaceholderIcon,
            )
            Text(stringResource(Res.string.error_generic, code), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(Res.string.retry))
            }
        }
    }
}

@Composable
private fun FeedSections(
    state: Pager.State.Ready,
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    hoistedListState: LazyListState? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    // Collapsible sections start collapsed; the map survives recomposition, keyed by section slot.
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = gridMetricsFor(maxWidth)
        val layout = feedLayoutFor(maxWidth)
        val columns = if (layout != FeedLayout.Grid) 1 else gridColumnsFor(maxWidth)
        val wide = layout == FeedLayout.WideList
        val listState = hoistedListState ?: rememberLazyListState()
        // Report scroll to the shell (which lifts the app bar); the feed doesn't touch the bar itself.
        TrackScroll { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (topContent != null) item { topContent() }
            item { FeedChips(state, pager) }
            if (topContent == null && state.chips.isEmpty()) {
                // Nothing above the first item (search results, channel content tabs) — the
                // mockups give the feed a small inset under the app bar (search.html .grid).
                item { Spacer(Modifier.height(6.dp)) }
            }
            state.sections.forEachIndexed { index, section ->
                feedSection(
                    section = section,
                    columns = columns,
                    metrics = metrics,
                    wide = wide,
                    expanded = expanded[index] ?: !section.collapsible,
                    onToggle = { expanded[index] = !(expanded[index] ?: !section.collapsible) },
                    coordinator = coordinator,
                    onOpen = onOpen,
                    onPlayItem = onPlayItem,
                    onDeeplink = onDeeplink,
                )
            }
            if (state.continuation != null) {
                item {
                    if (state.loadingMore) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    } else {
                        ShowMoreButton(stringResource(Res.string.show_more), expanded = false, onClick = { pager.loadMore() })
                    }
                }
            }
        }
    }
}

/** Pills shown while a collapsible tag section is collapsed (roughly two rows). */
private const val TAGS_COLLAPSED = 10

private fun androidx.compose.foundation.lazy.LazyListScope.feedSection(
    section: YtSection,
    columns: Int,
    metrics: GridMetrics,
    wide: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
    onOpen: ((YtFeedItem) -> Unit)? = null,
    onPlayItem: ((YtFeedItem) -> Unit)? = null,
    onDeeplink: ((link: String, title: String?) -> Unit)? = null,
) {
    if (section.header != null || section.collapsible) {
        item {
            val deeplink = section.header?.ctaDeeplink
            SectionHeader(
                section.header,
                collapsed = if (section.collapsible) !expanded else null,
                onToggle = if (section.collapsible) onToggle else null,
                onCta = if (deeplink != null && onDeeplink != null) {
                    { onDeeplink(deeplink, section.header?.title) }
                } else {
                    null
                },
            )
        }
    }
    // A tag section (Explore's Moods & genres, its entry buttons) is a wrap of navigation pills, not
    // list rows or grid cards. A long wrap (37 mood/genre tiles) collapses to a couple rows behind a
    // "Show more" toggle — the header's chevron and the button below both expand it.
    if (section.layout == YtSection.LAYOUT_TAGS) {
        val shown = if (expanded || !section.collapsible) section.items else section.items.take(TAGS_COLLAPSED)
        item { TagFlow(shown, onOpen) }
        if (section.collapsible && section.items.size > TAGS_COLLAPSED) {
            item {
                val label = if (expanded) {
                    stringResource(Res.string.show_less)
                } else {
                    stringResource(Res.string.show_more_n, section.items.size - TAGS_COLLAPSED)
                }
                ShowMoreButton(label, expanded = expanded, onClick = onToggle)
            }
        }
        return
    }
    // Collapsed shows two rows: 2 items in a single-column list, two grid rows otherwise.
    val collapsedCount = if (columns == 1) 2 else columns * 2
    val shown = if (expanded) section.items else section.items.take(collapsedCount)
    if (columns == 1) {
        items(shown.size) { i ->
            val item = shown[i]
            FeedListItem(
                item,
                actions = itemActions(item, coordinator),
                onClick = itemClick(item, coordinator, onOpen, onPlayItem),
                wide = wide,
            )
        }
    } else {
        val rows = shown.chunked(columns)
        items(rows.size) { r ->
            Row(
                // Margin/gutter minus the inset each card re-adds around itself as the hover's
                // breathing room — visual metrics stay margin/gutter exactly. Rows sit a full
                // Space16 apart vertically regardless of the width class's (narrower) gutter —
                // the card meta under each thumb needs more separation than thumbs side by side.
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = metrics.margin - metrics.inset,
                        end = metrics.margin - metrics.inset,
                        bottom = (DesignSpacing.Space16 - metrics.inset * 2).coerceAtLeast(0.dp),
                    ),
                horizontalArrangement = Arrangement.spacedBy(metrics.gutter - metrics.inset * 2),
            ) {
                rows[r].forEach { item ->
                    FeedGridCard(
                        item,
                        actions = itemActions(item, coordinator),
                        onClick = itemClick(item, coordinator, onOpen, onPlayItem),
                        modifier = Modifier.weight(1f),
                        inset = metrics.inset,
                    )
                }
                repeat(columns - rows[r].size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    if (section.collapsible && section.items.size > collapsedCount) {
        item {
            val label = if (expanded) {
                stringResource(Res.string.show_less)
            } else {
                stringResource(Res.string.show_more_n, section.items.size - collapsedCount)
            }
            ShowMoreButton(label, expanded = expanded, onClick = onToggle)
        }
    }
}

private fun itemClick(
    item: YtFeedItem,
    coordinator: PlaybackCoordinator,
    onOpen: ((YtFeedItem) -> Unit)?,
    onPlayItem: ((YtFeedItem) -> Unit)?,
): (() -> Unit)? = when (item.kind) {
    YtFeedItem.KIND_VIDEO -> ({ onPlayItem?.invoke(item) ?: coordinator.playNow(item) })
    YtFeedItem.KIND_PLAYLIST, YtFeedItem.KIND_CHANNEL, YtFeedItem.KIND_TAG -> onOpen?.let { { it(item) } }
    else -> null
}

@Composable
private fun itemActions(item: YtFeedItem, coordinator: PlaybackCoordinator): ItemActions = ItemActions(
    if (item.kind == YtFeedItem.KIND_VIDEO) {
        listOf(
            stringResource(Res.string.queue_next) to { coordinator.playNext(item) },
            stringResource(Res.string.queue_last) to { coordinator.playLater(item) },
        )
    } else {
        emptyList()
    },
)

/** The feed filter chips ("All / Music / Gaming…"); tapping one reloads the feed through its token. */
@Composable
private fun FeedChips(state: Pager.State.Ready, pager: Pager) {
    if (state.chips.isEmpty()) return
    val chipScroll = rememberLazyListState()
    LazyRow(
        Modifier.fillMaxWidth().horizontalWheelScroll(chipScroll),
        state = chipScroll,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.chips) { chip ->
            // Keep-content reload: the list stays put with the pull indicator spinning, so a failed
            // chip fetch keeps the current data (error → snackbar) instead of blanking the screen.
            FeedChip(chip.title, selected = chip.selected) { pager.reload(chip.token) }
        }
    }
}

/** One feed filter pill. */
@Composable
internal fun FeedChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        title,
        style = TextStyle(fontSize = 12.sp, color = if (selected) Color.White else DesignColors.InkSoft),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) DesignColors.Accent else Color.Transparent)
            .border(1.dp, if (selected) DesignColors.Accent else DesignColors.Outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * Music-first ordering for the Home feed (a music app's home): YouTube Mixes (the endless RD…
 * radios) first, then music videos, then videos from an Official Artist Channel, then verified
 * channels, then the rest. Every other item type (regular playlists, channels) is PINNED — it
 * keeps its exact slot while the ranked items reorder through the remaining slots (stable within
 * each band).
 */
internal fun rankMusicFirst(items: List<YtFeedItem>): List<YtFeedItem> {
    fun band(item: YtFeedItem): Int? = when {
        item.kind == YtFeedItem.KIND_PLAYLIST && item.id.startsWith("RD") -> 0
        item.kind != YtFeedItem.KIND_VIDEO -> null // pinned
        item.musicVideo -> 1
        item.ownerBadge == YtFeedItem.OWNER_BADGE_ARTIST -> 2
        item.ownerBadge == YtFeedItem.OWNER_BADGE_VERIFIED -> 3
        else -> 4
    }
    val movable = items.filter { band(it) != null }.sortedBy { band(it) }
    var next = 0
    return items.map { item -> if (band(item) == null) item else movable[next++] }
}

/** The three feed layout bands a surface width falls into. */
internal enum class FeedLayout { SquareList, WideList, Grid }

/** The ONE place the feed breakpoints live: square list under 400dp, 16:9 list to 469dp, then the
 *  grid ([gridColumnsFor] columns). */
internal fun feedLayoutFor(width: androidx.compose.ui.unit.Dp): FeedLayout = when {
    width < 400.dp -> FeedLayout.SquareList
    width < 470.dp -> FeedLayout.WideList
    else -> FeedLayout.Grid
}

/** Max-item-width model: the FEWEST columns (≥2) keeping every card ≤ [GRID_CARD_MAX_WIDTH], with
 *  the gutter between columns counted. Cards shrink toward the next column, never past the max. */
internal fun gridColumnsFor(width: androidx.compose.ui.unit.Dp): Int {
    val metrics = gridMetricsFor(width)
    return kotlin.math.ceil((width - metrics.margin * 2 + metrics.gutter) / (GRID_CARD_MAX_WIDTH + metrics.gutter))
        .toInt()
        .coerceAtLeast(2)
}

@Composable
internal fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
