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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpcommon.error.CodedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtChip
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtSection
import ymusic.ui.resources.Res
import ymusic.ui.resources.error_generic
import ymusic.ui.resources.feed_empty
import ymusic.ui.resources.queue_last
import ymusic.ui.resources.queue_next
import ymusic.ui.resources.retry
import ymusic.ui.resources.show_less
import ymusic.ui.resources.show_more
import ymusic.ui.resources.show_more_n

/** Paged feed loading over any yt page source (browse / search / watch-next continuations). */
class Pager(
    private val scope: CoroutineScope,
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
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** (Re)load from the top; [chipToken] re-requests the feed filtered by that chip. */
    fun refresh(chipToken: String? = null) {
        job?.cancel()
        val previousChips = (_state.value as? State.Ready)?.chips.orEmpty()
        _state.value = State.Loading
        job = scope.launch {
            _state.value = try {
                val page = fetch(chipToken)
                // A chip-filtered page often carries no chip row of its own — keep the one we came from.
                val chips = page.chips.ifEmpty { previousChips }
                State.Ready(
                    sections = page.sections,
                    chips = chips,
                    selectedChipToken = chipToken ?: chips.firstOrNull { it.selected }?.token,
                    continuation = page.continuation,
                    loadingMore = false,
                )
            } catch (e: Exception) {
                State.Failed((e as? CodedError)?.code ?: "E20030", e.message)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? State.Ready ?: return
        val token = current.continuation ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        job = scope.launch {
            _state.value = try {
                val page = fetch(token)
                current.copy(
                    sections = appendSections(current.sections, page.sections),
                    continuation = page.continuation,
                    loadingMore = false,
                )
            } catch (e: Exception) {
                // Keep what we have; the tail button stays for a retry.
                current.copy(loadingMore = false)
            }
        }
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
 * One feed surface (design/final/feed-example.html): filter chips + sections, each rendered as a
 * list on a phone-portrait width or as a 2/3-column grid on tablet/landscape widths. Item tap /
 * queue actions come from [coordinator].
 */
@Composable
fun FeedList(
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    emptyMessage: String = stringResource(Res.string.feed_empty),
) {
    val state by pager.state.collectAsState()
    when (val s = state) {
        is Pager.State.Loading -> CenteredBox(modifier) { CircularProgressIndicator() }
        is Pager.State.Failed -> CenteredBox(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.error_generic, s.code), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { pager.refresh() }) { Text(stringResource(Res.string.retry)) }
            }
        }
        is Pager.State.Ready -> {
            if (s.sections.all { it.items.isEmpty() }) {
                Column(modifier) {
                    FeedChips(s, pager)
                    CenteredBox { Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                FeedSections(s, pager, coordinator, modifier)
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
) {
    // Collapsible sections start collapsed; the map survives recomposition, keyed by section slot.
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val metrics = gridMetricsFor(maxWidth)
        val layout = feedLayoutFor(maxWidth)
        val columns = if (layout != FeedLayout.Grid) {
            1
        } else {
            ((maxWidth - metrics.margin * 2 + metrics.gutter) / (GRID_CARD_TARGET + metrics.gutter))
                .toInt()
                .coerceAtLeast(2)
        }
        val wide = layout == FeedLayout.WideList
        LazyColumn(Modifier.fillMaxSize()) {
            item { FeedChips(state, pager) }
            state.sections.forEachIndexed { index, section ->
                feedSection(
                    section = section,
                    columns = columns,
                    metrics = metrics,
                    wide = wide,
                    expanded = expanded[index] ?: !section.collapsible,
                    onToggle = { expanded[index] = !(expanded[index] ?: !section.collapsible) },
                    coordinator = coordinator,
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

private fun androidx.compose.foundation.lazy.LazyListScope.feedSection(
    section: YtSection,
    columns: Int,
    metrics: GridMetrics,
    wide: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    coordinator: PlaybackCoordinator,
) {
    if (section.header != null || section.collapsible) {
        item {
            SectionHeader(
                section.header,
                collapsed = if (section.collapsible) !expanded else null,
                onToggle = if (section.collapsible) onToggle else null,
            )
        }
    }
    val collapsedCount = if (columns == 1) 2 else columns
    val shown = if (expanded) section.items else section.items.take(collapsedCount)
    if (columns == 1) {
        items(shown.size) { i ->
            val item = shown[i]
            FeedListItem(
                item,
                actions = itemActions(item, coordinator),
                onClick = itemClick(item, coordinator),
                small = section.collapsible && !expanded,
                wide = wide,
            )
        }
    } else {
        val rows = shown.chunked(columns)
        items(rows.size) { r ->
            Row(
                // Margin/gutter minus the inset each card re-adds around itself as the hover's
                // breathing room — visual metrics stay margin/gutter exactly.
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.margin - metrics.inset),
                horizontalArrangement = Arrangement.spacedBy(metrics.gutter - metrics.inset * 2),
            ) {
                rows[r].forEach { item ->
                    FeedGridCard(
                        item,
                        actions = itemActions(item, coordinator),
                        onClick = itemClick(item, coordinator),
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

private fun itemClick(item: YtFeedItem, coordinator: PlaybackCoordinator): (() -> Unit)? =
    if (item.kind == YtFeedItem.KIND_VIDEO) ({ coordinator.playNow(item) }) else null

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
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.chips) { chip ->
            FeedChip(chip.title, selected = chip.token == state.selectedChipToken) { pager.refresh(chip.token) }
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
            .background(if (selected) DesignColors.Ink else Color.Transparent)
            .border(1.dp, if (selected) DesignColors.Ink else DesignColors.Outline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** The three feed layout bands a surface width falls into. */
internal enum class FeedLayout { SquareList, WideList, Grid }

/** The ONE place the feed breakpoints live: square list under 400dp, 16:9 list to 469dp, then as
 *  many ~[GRID_CARD_TARGET] grid cards as fit. */
internal fun feedLayoutFor(width: androidx.compose.ui.unit.Dp): FeedLayout = when {
    width < 400.dp -> FeedLayout.SquareList
    width < 470.dp -> FeedLayout.WideList
    else -> FeedLayout.Grid
}

@Composable
internal fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
