package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kmpcommon.error.CodedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_back
import ymusic.ui.resources.library_add_folder
import ymusic.ui.resources.library_crumb_root
import ymusic.ui.resources.library_empty
import ymusic.ui.resources.library_empty_folder
import ymusic.ui.resources.library_folder
import ymusic.ui.resources.library_no_playlists
import ymusic.ui.resources.library_rescan
import ymusic.ui.resources.library_scan_failed
import ymusic.ui.resources.library_scan_folder
import ymusic.ui.resources.library_scanning
import ymusic.ui.resources.library_tab_albums
import ymusic.ui.resources.library_tab_artists
import ymusic.ui.resources.library_tab_folders
import ymusic.ui.resources.library_tab_genres
import ymusic.ui.resources.library_tab_playlists
import ymusic.ui.resources.library_tab_songs
import ymusic.ui.resources.library_unavailable
import ymusic.ui.resources.n_songs
import ymusic.ui.resources.unknown_album
import ymusic.ui.resources.unknown_artist
import ymusic.ui.resources.unknown_genre

private enum class LibraryTab(val label: StringResource) {
    Folders(Res.string.library_tab_folders),
    Playlists(Res.string.library_tab_playlists),
    Songs(Res.string.library_tab_songs),
    Artists(Res.string.library_tab_artists),
    Albums(Res.string.library_tab_albums),
    Genres(Res.string.library_tab_genres),
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
            Text(stringResource(Res.string.library_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val content by remember(store) {
        merge(store.itemsChanges(), store.bucketsChanges())
            .onStart { emit(Unit) }
            .conflate()
            // A running scan fires change events per file/folder; the pause coalesces the storm to
            // a few reloads per second (conflate holds the latest while we wait + load).
            .map {
                delay(200)
                LibraryContent(store.allItems(), store.allBuckets().isNotEmpty())
            }
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
            // Keeps each tab's saveable state (folder path, scroll) while another tab is showing.
            val tabStateHolder = rememberSaveableStateHolder()
            Column(Modifier.fillMaxSize()) {
                val chipScroll = rememberScrollState()
                HorizontalOverflowBox(chipScroll) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScroll)
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibraryTab.entries.forEach { t ->
                            LibraryChip(stringResource(t.label), selected = t == tab) { tab = t }
                        }
                    }
                }
                tabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        LibraryTab.Folders -> FoldersTab(store, loaded, coordinator, graph.scanLibraryFolder, graph.rescanLibrary, scanState)
                        LibraryTab.Playlists -> PlaylistsTab(store, loaded, coordinator)
                        LibraryTab.Songs -> Column(Modifier.fillMaxSize()) {
                            SectionHeader(
                                YtSectionHeader(
                                    title = stringResource(Res.string.library_tab_songs),
                                    count = "${loaded.songs.size}",
                                ),
                            )
                            SongList(loaded.songs, coordinator)
                        }
                        LibraryTab.Artists ->
                            GroupedList(
                                loaded.songs,
                                Icons.Default.Person,
                                stringResource(Res.string.unknown_artist),
                                coordinator,
                            ) { it.artist }
                        LibraryTab.Albums ->
                            GroupedList(
                                loaded.songs,
                                Icons.Default.Album,
                                stringResource(Res.string.unknown_album),
                                coordinator,
                            ) { it.album }
                        LibraryTab.Genres ->
                            GroupedList(
                                loaded.songs,
                                Icons.Default.Category,
                                stringResource(Res.string.unknown_genre),
                                coordinator,
                            ) { it.genre }
                    }
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
            Text(stringResource(Res.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scan == null) return@Column
            ScanPill(scan, state, label = stringResource(Res.string.library_scan_folder))
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
    rescan: (suspend () -> String?)?,
    scanState: ScanState,
) {
    // (id, displayName) instead of the bucket itself so the browse position survives the section
    // leaving composition (saveable): coming back from Discover reopens the same folder.
    var path by rememberSaveable(stateSaver = CrumbPathSaver) { mutableStateOf(listOf<Pair<Long, String>>()) }
    var folders by remember { mutableStateOf<List<MediaStoreBucket>?>(null) }
    var items by remember { mutableStateOf<List<MediaStoreItem>>(emptyList()) }

    LaunchedEffect(path, content) {
        val currentId = path.lastOrNull()?.first
        folders = store.childBuckets(currentId)
        items = store.itemsInBucket(currentId)
    }

    Column(Modifier.fillMaxSize()) {
        Breadcrumb(path.map { it.second }) { depth -> path = path.take(depth) }
        if (path.isEmpty() && scan != null) {
            ScanBar(scan, rescan, scanState, label = stringResource(Res.string.library_add_folder))
        }
        val children = folders
        if (children == null) {
            CenteredBox { CircularProgressIndicator() }
            return@Column
        }
        val openFolder = { bucket: MediaStoreBucket -> path = path + (bucket.id to bucket.displayName) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            when {
                children.isEmpty() && items.isEmpty() -> Text(
                    stringResource(Res.string.library_empty_folder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                // Large screens: folders as an album-card grid (max-width cards), this folder's songs
                // as a wider list below them (see design/final/library.html). Songs are min-width
                // sized — full-width when tight, splitting as the screen grows — and rendered as
                // full-width weighted rows so they stay gap-free whatever the folder column count.
                maxWidth >= LibraryGridMinWidth -> {
                    val columns = libraryGridColumns(maxWidth)
                    val songsPerRow = librarySongColumns(maxWidth)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        items(children, key = { "b${it.id}" }) { bucket ->
                            LibraryGridCard(
                                icon = Icons.Default.Folder,
                                artUrl = null,
                                title = bucket.displayName,
                                subtitle = stringResource(Res.string.library_folder),
                            ) { openFolder(bucket) }
                        }
                        // Full-span rows break the flow onto a fresh line below the folders.
                        items(
                            items.indices.chunked(songsPerRow),
                            key = { items[it.first()].id },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { group ->
                            Row(Modifier.fillMaxWidth()) {
                                group.forEach { i ->
                                    Box(Modifier.weight(1f)) { SongRow(items[i]) { coordinator.playLocal(items, i) } }
                                }
                                repeat(songsPerRow - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(children, key = { "b${it.id}" }) { bucket ->
                        FolderRow(bucket) { openFolder(bucket) }
                    }
                    itemsIndexed(items, key = { _, s -> s.id }) { index, song ->
                        SongRow(song) { coordinator.playLocal(items, index) }
                    }
                }
            }
        }
    }
}

/** The width at/above which the Library's browse grids appear (below it, a single-column list). The
 *  Library sets its own breakpoint rather than borrowing the Discover video feed's. */
internal val LibraryGridMinWidth = 480.dp

/** Library grids size cards by a MAXIMUM width, not an adaptive minimum: the fewest columns that
 *  keep every card ≤ this width, so cards shrink toward the next column instead of ballooning toward
 *  double the minimum, and the column count climbs one at a time (no 2→4 jump). */
private val LibraryCardMaxWidth = 176.dp

private fun libraryGridColumns(width: androidx.compose.ui.unit.Dp): Int {
    val avail = (width - 20.dp).coerceAtLeast(0.dp) // the grid's 10dp side padding
    return kotlin.math.ceil(avail / LibraryCardMaxWidth).toInt().coerceAtLeast(2)
}

/** A song row wants a comfortable list width (thumb + title + artist), so — unlike the cards — it's
 *  sized by a MINIMUM width: fit as many songs a row as keep each at least this wide, dropping to a
 *  single full-width song when there's only room for one, rather than cramming two narrow ones. So
 *  at the tightest grid (3 folder columns) a song is full width, then splits as the screen grows. */
private val LibrarySongMinWidth = 360.dp

internal fun librarySongColumns(width: androidx.compose.ui.unit.Dp): Int {
    val avail = (width - 20.dp).coerceAtLeast(0.dp)
    return (avail / LibrarySongMinWidth).toInt().coerceAtLeast(1)
}

/** Saves the folder browse path as a flat [id, name, id, name, …] list. */
private val CrumbPathSaver = listSaver<List<Pair<Long, String>>, Any>(
    save = { path -> path.flatMap { (id, name) -> listOf(id, name) } },
    restore = { flat -> flat.chunked(2).map { (id, name) -> id as Long to name as String } },
)

/** `Library › root › sub` — every crumb but the last navigates back up to it. */
@Composable
internal fun Breadcrumb(path: List<String>, onNavigate: (depth: Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbText(stringResource(Res.string.library_crumb_root), last = path.isEmpty()) { onNavigate(0) }
        path.forEachIndexed { i, name ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(16.dp),
                tint = DesignColors.InkFaint,
            )
            CrumbText(name, last = i == path.lastIndex) { onNavigate(i + 1) }
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
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = !last, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun FolderRow(bucket: MediaStoreBucket, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LibraryThumb(Icons.Default.Folder, badge = null)
        Column(Modifier.weight(1f)) {
            Text(bucket.displayName, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.library_folder), style = ListSubStyle, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DesignColors.InkFaint)
    }
}

private val MediaStoreBucket.displayName: String get() = aliasName?.takeIf(String::isNotBlank) ?: name

// ---- playlists ----

@Composable
private fun PlaylistsTab(store: BetterMediaStore, content: LibraryContent, coordinator: PlaybackCoordinator) {
    // (id, name) — saveable, so the opened playlist survives the tab leaving composition.
    var selected by rememberSaveable(stateSaver = OpenPlaylistSaver) { mutableStateOf<Pair<Long, String>?>(null) }
    var lists by remember { mutableStateOf<List<Pair<Playlist, Int>>?>(null) }
    var members by remember { mutableStateOf<List<MediaStoreItem>?>(null) }

    LaunchedEffect(selected, content) {
        val open = selected
        if (open == null) {
            lists = store.playlists().map { it to store.playlistItems(it.id).size }
        } else {
            members = store.playlistItemDetails(open.first).map { it.mediaItem }
        }
    }

    val open = selected
    if (open == null) {
        val loaded = lists
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator() }
            loaded.isEmpty() -> CenteredBox {
                Text(stringResource(Res.string.library_no_playlists), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LibraryBrowseLevel(
                entries = loaded,
                key = { (p, _) -> p.id },
                row = { (playlist, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                members = null
                                selected = playlist.id to playlist.name
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        LibraryThumb(Icons.AutoMirrored.Filled.PlaylistPlay, badge = "$count")
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                pluralStringResource(Res.plurals.n_songs, count, count),
                                style = ListSubStyle,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                },
                card = { (playlist, count) ->
                    LibraryGridCard(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        artUrl = null,
                        title = playlist.name,
                        subtitle = pluralStringResource(Res.plurals.n_songs, count, count),
                    ) {
                        members = null
                        selected = playlist.id to playlist.name
                    }
                },
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            DrillDownHeader(open.second) { selected = null }
            val songs = members
            if (songs == null) {
                CenteredBox { CircularProgressIndicator() }
            } else {
                SongList(songs, coordinator)
            }
        }
    }
}

/** Saves the opened playlist as [id, name] (empty = none open). */
private val OpenPlaylistSaver = listSaver<Pair<Long, String>?, Any>(
    save = { open -> open?.let { listOf(it.first, it.second) } ?: emptyList() },
    restore = { flat -> if (flat.isEmpty()) null else flat[0] as Long to flat[1] as String },
)

// ---- shared pieces ----

/** The outlined chip from the mockup's quick-action row (`.qa .q`). */
@Composable
internal fun LibraryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else DesignColors.Ink,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) DesignColors.Accent else Color.Transparent)
            .border(1.dp, if (selected) DesignColors.Accent else DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** Scan-in-flight state, shared by the empty-state CTA and the tab-view scan bar. */
private class ScanState {
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
}

/** Runs a scan action through [state] (running flag + summary/error line); shared by the pill and
 *  the rescan button so both report through the same status line. */
private fun runScanAction(scope: CoroutineScope, action: suspend () -> String?, state: ScanState) {
    state.running = true
    scope.launch {
        state.message = try {
            action()
        } catch (e: Exception) {
            getString(Res.string.library_scan_failed, (e as? CodedError)?.code ?: e.message ?: "$e")
        } finally {
            state.running = false
        }
    }
}

/** The outlined pill that starts a scan; disabled while one runs. */
@Composable
private fun ScanPill(scan: suspend () -> String?, state: ScanState, label: String) {
    val scope = rememberCoroutineScope()
    Text(
        if (state.running) stringResource(Res.string.library_scanning) else label,
        style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(enabled = !state.running) { runScanAction(scope, scan, state) }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** The scan action inside the Folders tab: add-folder pill + optional rescan button + summary. */
@Composable
private fun ScanBar(scan: suspend () -> String?, rescan: (suspend () -> String?)?, state: ScanState, label: String) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanPill(scan, state, label = label)
        if (rescan != null) {
            IconButton(
                onClick = { runScanAction(scope, rescan, state) },
                enabled = !state.running,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.library_rescan),
                    modifier = Modifier.size(19.dp),
                    tint = DesignColors.InkSoft,
                )
            }
        }
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
internal fun SongList(songs: List<MediaStoreItem>, coordinator: PlaybackCoordinator, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Same min-width sizing as the Folders tab's songs: as many columns as keep each song
        // ≥ LibrarySongMinWidth, dropping to a single full-width column when that's all that fits.
        if (maxWidth >= LibraryGridMinWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(librarySongColumns(maxWidth)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(songs.size, key = { songs[it].id }) { index ->
                    SongRow(songs[index]) { coordinator.playLocal(songs, index) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(song) { coordinator.playLocal(songs, index) }
                }
            }
        }
    }
}

@Composable
internal fun SongRow(song: MediaStoreItem, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LibraryThumb(Icons.Default.MusicNote, formatDuration(song.durationMs), artUrl = localArtUrl(song))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(song.artist, song.album).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = ListSubStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
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
    // Saveable: the opened artist/album/genre survives the tab leaving composition.
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    // Grouping 10k songs is real CPU work — done off-main, with the previous grouping (or nothing
    // on first entry) showing until the fresh one lands.
    var groups by remember { mutableStateOf<List<Pair<String, List<MediaStoreItem>>>?>(null) }
    LaunchedEffect(songs) {
        groups = withContext(Dispatchers.Default) {
            songs.groupBy { keyOf(it)?.takeIf(String::isNotBlank) ?: unknownLabel }
                .toList()
                .sortedBy { (name, _) -> name.lowercase() }
        }
    }
    val grouped = groups
    if (grouped == null) {
        CenteredBox { CircularProgressIndicator() }
        return
    }

    val selected = group
    if (selected == null) {
        LibraryBrowseLevel(
            entries = grouped,
            key = { it.first },
            row = { (name, members) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { group = name }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    LibraryThumb(icon, badge = null, artUrl = members.firstNotNullOfOrNull(::localArtUrl))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(Res.plurals.n_songs, members.size, members.size),
                            style = ListSubStyle,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            card = { (name, members) ->
                LibraryGridCard(
                    icon = icon,
                    artUrl = members.firstNotNullOfOrNull(::localArtUrl),
                    title = name,
                    subtitle = pluralStringResource(Res.plurals.n_songs, members.size, members.size),
                ) { group = name }
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            DrillDownHeader(selected) { group = null }
            SongList(grouped.firstOrNull { it.first == selected }?.second.orEmpty(), coordinator)
        }
    }
}

/** One browse level (playlists / artists / albums / genres): rows on phone widths, an album-card
 *  grid on the feed's Grid band (≥470dp) — the legacy Albums tab's large-screen treatment. */
@Composable
private fun <T> LibraryBrowseLevel(
    entries: List<T>,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
    card: @Composable (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= LibraryGridMinWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(libraryGridColumns(maxWidth)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                items(entries, key = key) { entry -> card(entry) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = key) { entry -> row(entry) }
            }
        }
    }
}

/** The grid form of a browse entry: square art tile (icon placeholder under the art) + title/count. */
@Composable
private fun LibraryGridCard(
    icon: ImageVector,
    artUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(DesignColors.Placeholder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = DesignColors.PlaceholderIcon)
            ArtImage(artUrl, RoundedCornerShape(10.dp))
        }
        Text(
            title,
            style = GridTitleStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(subtitle, style = GridSubStyle, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun DrillDownHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back)) }
        Text(
            title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun LibraryThumb(icon: ImageVector, badge: String?, artUrl: String? = null) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(DesignColors.Placeholder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = DesignColors.PlaceholderIcon)
        ArtImage(artUrl, RoundedCornerShape(10.dp))
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

private fun formatDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
