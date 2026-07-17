package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist

private enum class LibraryTab(val label: String) {
    Folders("Folders"),
    Playlists("Playlists"),
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
    Genres("Genres"),
}

/** Everything the tabs render from, reloaded on any items/buckets change. Identity doubles as the
 *  change tick the per-tab loaders key their LaunchedEffects on. */
private class LibraryContent(
    val songs: List<MediaStoreItem>,
    val hasBuckets: Boolean,
) {
    val isEmpty get() = songs.isEmpty() && !hasBuckets
}

/** The local library over [AppGraph.mediaStore]: an empty state with the scan action until the first
 *  scan lands, then the Folders/Playlists/Songs/Artists/Albums/Genres tabs. Styled with
 *  design/final/library.html's chips + section-header + list-row language (the account half of that
 *  mockup lands with sign-in). */
@Composable
fun LibraryScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val store = graph.mediaStore
    if (store == null) {
        CenteredBox {
            Text("No local library configured on this platform", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val content by remember(store) {
        merge(store.itemsChanges(), store.bucketsChanges())
            .onStart { emit(Unit) }
            .conflate()
            .map { LibraryContent(loadSongs(store), store.allBuckets().isNotEmpty()) }
    }.collectAsState(initial = null)

    // Hoisted so a scan started from the empty state keeps its spinner/summary when the first
    // indexed items flip the screen over to the tabs.
    val scanState = remember { ScanState() }

    val loaded = content
    when {
        loaded == null -> CenteredBox { CircularProgressIndicator() }
        loaded.isEmpty -> EmptyLibrary(graph.scanLibraryFolder, scanState)
        else -> {
            var tab by rememberSaveable { mutableStateOf(LibraryTab.Folders) }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryTab.entries.forEach { t ->
                        LibraryChip(t.label, selected = t == tab) { tab = t }
                    }
                }
                when (tab) {
                    LibraryTab.Folders -> FoldersTab(store, loaded, coordinator, graph.scanLibraryFolder, scanState)
                    LibraryTab.Playlists -> PlaylistsTab(store, loaded, coordinator)
                    LibraryTab.Songs -> Column(Modifier.fillMaxSize()) {
                        SectionHeader(YtSectionHeader(title = "Songs", count = "${loaded.songs.size}"))
                        SongList(loaded.songs, coordinator)
                    }
                    LibraryTab.Artists ->
                        GroupedList(loaded.songs, Icons.Default.Person, "Unknown artist", coordinator) { it.artist }
                    LibraryTab.Albums ->
                        GroupedList(loaded.songs, Icons.Default.Album, "Unknown album", coordinator) { it.album }
                    LibraryTab.Genres ->
                        GroupedList(loaded.songs, Icons.Default.Category, "Unknown genre", coordinator) { it.genre }
                }
            }
        }
    }
}

/** First-open state: nothing indexed yet — just the scan call-to-action. */
@Composable
private fun EmptyLibrary(scan: (suspend () -> String?)?, state: ScanState) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your library is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scan == null) return@Column
            ScanPill(scan, state, label = "+  Scan folder")
            if (state.running) CircularProgressIndicator(Modifier.size(22.dp))
            state.message?.let {
                Text(it, style = TextStyle(fontSize = 11.5.sp, color = DesignColors.InkSecondary))
            }
        }
    }
}

// ---- folders ----

/** Browse the bucket tree: breadcrumb + child folders + this folder's songs (root = orphan items).
 *  The root level carries the "+ Add folder" scan action, so more folders can join the library. */
@Composable
private fun FoldersTab(
    store: BetterMediaStore,
    content: LibraryContent,
    coordinator: PlaybackCoordinator,
    scan: (suspend () -> String?)?,
    scanState: ScanState,
) {
    var path by remember { mutableStateOf(listOf<MediaStoreBucket>()) }
    var folders by remember { mutableStateOf<List<MediaStoreBucket>?>(null) }
    var items by remember { mutableStateOf<List<MediaStoreItem>>(emptyList()) }

    LaunchedEffect(path, content) {
        val current = path.lastOrNull()
        folders = store.childBuckets(current?.id)
        items = store.itemsInBucket(current?.id)
    }

    Column(Modifier.fillMaxSize()) {
        Breadcrumb(path) { depth -> path = path.take(depth) }
        if (path.isEmpty() && scan != null) ScanBar(scan, scanState, label = "+  Add folder")
        val children = folders
        if (children == null) {
            CenteredBox { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(children, key = { "b${it.id}" }) { bucket ->
                FolderRow(bucket) { path = path + bucket }
            }
            itemsIndexed(items, key = { _, s -> s.id }) { index, song ->
                SongRow(song) { coordinator.playLocal(items, index) }
            }
            if (children.isEmpty() && items.isEmpty()) {
                item {
                    Text(
                        "Empty folder",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** `Library › root › sub` — every crumb but the last navigates back up to it. */
@Composable
private fun Breadcrumb(path: List<MediaStoreBucket>, onNavigate: (depth: Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbText("Library", last = path.isEmpty()) { onNavigate(0) }
        path.forEachIndexed { i, bucket ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(16.dp),
                tint = DesignColors.InkFaint,
            )
            CrumbText(bucket.displayName, last = i == path.lastIndex) { onNavigate(i + 1) }
        }
    }
}

@Composable
private fun CrumbText(label: String, last: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontSize = 12.5.sp,
            fontWeight = if (last) FontWeight.Bold else FontWeight.Medium,
            color = if (last) DesignColors.Ink else DesignColors.InkSoft,
        ),
        maxLines = 1,
        modifier = Modifier
            .clickable(enabled = !last, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

@Composable
private fun FolderRow(bucket: MediaStoreBucket, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryThumb(Icons.Default.Folder, badge = null)
        Column(Modifier.weight(1f)) {
            Text(bucket.displayName, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Folder", style = ListSubStyle, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DesignColors.InkFaint)
    }
}

private val MediaStoreBucket.displayName: String get() = aliasName?.takeIf(String::isNotBlank) ?: name

// ---- playlists ----

@Composable
private fun PlaylistsTab(store: BetterMediaStore, content: LibraryContent, coordinator: PlaybackCoordinator) {
    var selected by remember { mutableStateOf<Playlist?>(null) }
    var lists by remember { mutableStateOf<List<Pair<Playlist, Int>>?>(null) }
    var members by remember { mutableStateOf<List<MediaStoreItem>?>(null) }

    LaunchedEffect(selected, content) {
        val open = selected
        if (open == null) {
            lists = store.playlists().map { it to store.playlistItems(it.id).size }
        } else {
            members = store.playlistItemDetails(open.id).map { it.mediaItem }
        }
    }

    val open = selected
    if (open == null) {
        val loaded = lists
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator() }
            loaded.isEmpty() -> CenteredBox {
                Text("No playlists yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(loaded, key = { (p, _) -> p.id }) { (playlist, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                members = null
                                selected = playlist
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryThumb(Icons.AutoMirrored.Filled.PlaylistPlay, badge = "$count")
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "$count song" + if (count == 1) "" else "s",
                                style = ListSubStyle,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            DrillDownHeader(open.name) { selected = null }
            val songs = members
            if (songs == null) {
                CenteredBox { CircularProgressIndicator() }
            } else {
                SongList(songs, coordinator)
            }
        }
    }
}

// ---- shared pieces ----

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

/** Scan-in-flight state, shared by the empty-state CTA and the tab-view scan bar. */
private class ScanState {
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

/** The outlined pill that starts a scan; disabled while one runs. */
@Composable
private fun ScanPill(scan: suspend () -> String?, state: ScanState, label: String) {
    val scope = rememberCoroutineScope()
    Text(
        if (state.running) "Scanning…" else label,
        style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFF3A3A3A)),
        modifier = Modifier
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(enabled = !state.running) {
                state.running = true
                scope.launch {
                    state.message = try {
                        scan()
                    } catch (e: Exception) {
                        "Scan failed (${(e as? CodedError)?.code ?: e.message ?: e})"
                    } finally {
                        state.running = false
                    }
                }
            }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** The scan action inside the Folders tab: pill + last summary line. */
@Composable
private fun ScanBar(scan: suspend () -> String?, state: ScanState, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanPill(scan, state, label = label)
        if (state.running) CircularProgressIndicator(Modifier.size(20.dp))
        state.message?.let {
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

/** Group rows (artist/album/genre) with a one-level drill-down into the group's songs. */
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
            DrillDownHeader(selected) { group = null }
            SongList(groups.firstOrNull { it.first == selected }?.second.orEmpty(), coordinator)
        }
    }
}

@Composable
private fun DrillDownHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(
            title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
