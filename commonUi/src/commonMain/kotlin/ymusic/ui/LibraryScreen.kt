package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpcommon.error.CodedError
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreItem

/** Library sub-tabs; Artists/Albums group the songs list in memory. */
private enum class LibraryTab(val label: String) {
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
}

/** The local library over [AppGraph.mediaStore]: reactive song list + in-memory artist/album groups.
 *  Styled with design/final/library.html's chips + section-header + list-row language (the account
 *  half of that mockup lands with sign-in). */
@Composable
fun LibraryScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val store = graph.mediaStore
    if (store == null) {
        CenteredBox {
            Text("No local library configured on this platform", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }
    val songs by remember(store) {
        store.itemsChanges()
            .onStart { emit(Unit) }
            .conflate()
            .map { loadSongs(store) }
    }.collectAsState(initial = null)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTab.entries.forEach { t ->
                LibraryChip(t.label, selected = t == tab) { tab = t }
            }
        }
        graph.scanLibraryFolder?.let { ScanBar(it) }
        val loaded = songs
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator() }
            loaded.isEmpty() -> CenteredBox {
                Text(
                    if (graph.scanLibraryFolder != null) "Library is empty — scan a folder to index your music" else "Library is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> when (tab) {
                LibraryTab.Songs -> Column(Modifier.fillMaxSize()) {
                    SectionHeader(YtSectionHeader(title = "Songs", count = "${loaded.size}"))
                    SongList(loaded, coordinator)
                }
                LibraryTab.Artists -> GroupedList(loaded, Icons.Default.Person, "Unknown artist", coordinator) { it.artist }
                LibraryTab.Albums -> GroupedList(loaded, Icons.Default.Album, "Unknown album", coordinator) { it.album }
            }
        }
    }
}

/** The outlined chip from the mockup's quick-action row (`.qa .q`). */
@Composable
private fun LibraryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else Color(0xFF3A3A3A),
        ),
        modifier = Modifier
            .background(if (selected) DesignColors.Ink else Color.Transparent, RoundedCornerShape(999.dp))
            .border(1.dp, if (selected) DesignColors.Ink else DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** The shell scan action: pill button + last summary line; disabled while a scan runs. */
@Composable
private fun ScanBar(scan: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (running) "Scanning…" else "Scan folder…",
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF3A3A3A)),
            modifier = Modifier
                .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
                .clickable(enabled = !running) {
                    running = true
                    scope.launch {
                        message = try {
                            scan()
                        } catch (e: Exception) {
                            "Scan failed (${(e as? CodedError)?.code ?: e.message ?: e})"
                        } finally {
                            running = false
                        }
                    }
                }
                .padding(horizontal = 13.dp, vertical = 7.dp),
        )
        if (running) CircularProgressIndicator(Modifier.size(20.dp))
        message?.let {
            Text(
                it,
                style = TextStyle(fontSize = 11.5.sp, color = DesignColors.InkSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SongList(songs: List<MediaStoreItem>, coordinator: PlaybackCoordinator, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(song) { coordinator.playLocal(songs, index) }
        }
    }
}

@Composable
private fun SongRow(song: MediaStoreItem, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryThumb(Icons.Default.MusicNote, formatDuration(song.durationMs))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(song.artist, song.album).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = ListSubStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Group rows (artist/album) with a one-level drill-down into the group's songs. */
@Composable
private fun GroupedList(
    songs: List<MediaStoreItem>,
    icon: ImageVector,
    unknownLabel: String,
    coordinator: PlaybackCoordinator,
    keyOf: (MediaStoreItem) -> String?,
) {
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(songs) {
        songs.groupBy { keyOf(it)?.takeIf(String::isNotBlank) ?: unknownLabel }
            .toList()
            .sortedBy { (name, _) -> name.lowercase() }
    }

    val selected = group
    if (selected == null) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(groups, key = { _, (name, _) -> name }) { _, (name, members) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { group = name }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryThumb(icon, badge = null)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${members.size} song" + if (members.size == 1) "" else "s",
                            style = ListSubStyle,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { group = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    selected,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SongList(groups.firstOrNull { it.first == selected }?.second.orEmpty(), coordinator)
        }
    }
}

@Composable
private fun LibraryThumb(icon: ImageVector, badge: String?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(DesignColors.Placeholder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = DesignColors.PlaceholderIcon)
        badge?.let {
            Text(
                it,
                style = TextStyle(fontSize = 9.sp, color = Color.White),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(DesignColors.BadgeInk, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** All non-deleted items: orphans + every bucket's items, one sorted songs list. */
private suspend fun loadSongs(store: BetterMediaStore): List<MediaStoreItem> {
    val items = buildList {
        addAll(store.itemsInBucket(null))
        store.allBuckets().forEach { addAll(store.itemsInBucket(it.id)) }
    }
    return items.sortedBy { it.title.lowercase() }
}

private fun formatDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
