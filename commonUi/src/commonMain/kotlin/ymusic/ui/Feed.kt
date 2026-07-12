package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kmpcommon.error.CodedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtFeedPage

/** Paged feed loading over any yt page source (browse / search / watch-next continuations). */
class Pager(
    private val scope: CoroutineScope,
    private val fetch: suspend (continuation: String?) -> YtFeedPage,
) {
    sealed interface State {
        data object Loading : State

        data class Failed(val code: String, val detail: String?) : State

        data class Ready(val page: YtFeedPage, val items: List<YtFeedItem>, val loadingMore: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _state.value = State.Loading
        job = scope.launch {
            _state.value = try {
                val page = fetch(null)
                State.Ready(page, page.items, loadingMore = false)
            } catch (e: Exception) {
                State.Failed((e as? CodedError)?.code ?: "E20030", e.message)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? State.Ready ?: return
        val token = current.page.continuation ?: return
        if (current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        job = scope.launch {
            _state.value = try {
                val page = fetch(token)
                State.Ready(page, current.items + page.items, loadingMore = false)
            } catch (e: Exception) {
                // Keep what we have; surface the failure on the load-more row via state reset.
                State.Ready(current.page, current.items, loadingMore = false)
            }
        }
    }
}

/** One feed page: list of item rows + a load-more tail; the item tap/queue actions come from [coordinator]. */
@Composable
fun FeedList(
    pager: Pager,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Nothing here",
) {
    val state by pager.state.collectAsState()
    when (val s = state) {
        is Pager.State.Loading -> CenteredBox(modifier) { CircularProgressIndicator() }
        is Pager.State.Failed -> CenteredBox(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Something went wrong (${s.code})", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { pager.refresh() }) { Text("Retry") }
            }
        }
        is Pager.State.Ready -> {
            if (s.items.isEmpty()) {
                CenteredBox(modifier) { Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(modifier = modifier.fillMaxSize()) {
                    items(s.items, key = { "${it.kind}:${it.id}" }) { item ->
                        FeedRow(item, coordinator)
                    }
                    if (s.page.continuation != null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                if (s.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                } else {
                                    OutlinedButton(onClick = { pager.loadMore() }) { Text("Show more") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(item: YtFeedItem, coordinator: PlaybackCoordinator) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.kind == YtFeedItem.KIND_VIDEO) { coordinator.playNow(item) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(item)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(item.byline, item.views, item.published, item.subscribers, item.itemCount)
                .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.kind == YtFeedItem.KIND_VIDEO) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Queue next") },
                        onClick = { coordinator.playNext(item); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Queue last") },
                        onClick = { coordinator.playLater(item); menuOpen = false },
                    )
                }
            }
        }
    }
}

/** Thumbnail placeholder — remote image loading lands with the image-loader dependency choice. */
@Composable
private fun Thumb(item: YtFeedItem) {
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 60.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (item.kind) {
            YtFeedItem.KIND_CHANNEL -> Icons.Default.Person
            YtFeedItem.KIND_PLAYLIST -> Icons.Default.PlaylistPlay
            else -> Icons.Default.QueueMusic
        }
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        item.duration?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .background(MaterialTheme.colorScheme.scrim, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
internal fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
