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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kmpcommon.error.CodedError
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreItem

/** Library sub-tabs; Artists/Albums group the songs list in memory. */
private enum class LibraryTab(val label: String) {
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
}

/** The local library over [AppGraph.mediaStore]: reactive song list + in-memory artist/album groups. */
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
        TabRow(selectedTabIndex = tab.ordinal) {
            LibraryTab.entries.forEach { t ->
                Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.label) })
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
                LibraryTab.Songs -> SongList(loaded, coordinator)
                LibraryTab.Artists -> GroupedList(loaded, Icons.Default.Person, "Unknown artist", coordinator) { it.artist }
                LibraryTab.Albums -> GroupedList(loaded, Icons.Default.Album, "Unknown album", coordinator) { it.album }
            }
        }
    }
}

/** The shell scan action: button + last summary line; disabled while a scan runs. */
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
        OutlinedButton(
            enabled = !running,
            onClick = {
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
            },
        ) { Text(if (running) "Scanning…" else "Scan folder…") }
        if (running) CircularProgressIndicator(Modifier.size(20.dp))
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryThumb(Icons.Default.MusicNote, formatDuration(song.durationMs))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(song.artist, song.album).joinToString(" · ")
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryThumb(icon, badge = null)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${members.size} song" + if (members.size == 1) "" else "s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(selected, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SongList(groups.firstOrNull { it.first == selected }?.second.orEmpty(), coordinator)
        }
    }
}

@Composable
private fun LibraryThumb(icon: ImageVector, badge: String?) {
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 60.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        badge?.let {
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
