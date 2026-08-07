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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist
import ymusic.mediastore.model.PlaylistItemDetail
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.settings.put
import ymusic.ui.resources.Res
import ymusic.ui.resources.add_to_playlist_new
import ymusic.ui.resources.cd_add_to_playlist
import ymusic.ui.resources.cd_back
import ymusic.ui.resources.delete_from_disk_confirm
import ymusic.ui.resources.delete_from_disk_message
import ymusic.ui.resources.delete_from_disk_title
import ymusic.ui.resources.dialog_cancel
import ymusic.ui.resources.library_add_folder
import ymusic.ui.resources.library_crumb_root
import ymusic.ui.resources.library_empty
import ymusic.ui.resources.library_empty_folder
import ymusic.ui.resources.library_folder
import ymusic.ui.resources.library_rescan
import ymusic.ui.resources.library_scan_folder
import ymusic.ui.resources.library_scanning
import ymusic.ui.resources.library_scanning_count
import ymusic.ui.resources.library_tab_albums
import ymusic.ui.resources.library_tab_artists
import ymusic.ui.resources.library_tab_folders
import ymusic.ui.resources.library_tab_genres
import ymusic.ui.resources.library_tab_playlists
import ymusic.ui.resources.library_tab_songs
import ymusic.ui.resources.library_unavailable
import ymusic.ui.resources.menu_delete_playlist
import ymusic.ui.resources.menu_download_location
import ymusic.ui.resources.menu_exclude_folder
import ymusic.ui.resources.menu_include_folder
import ymusic.ui.resources.menu_play
import ymusic.ui.resources.menu_remove_folder
import ymusic.ui.resources.menu_remove_from_disk
import ymusic.ui.resources.menu_remove_from_playlist
import ymusic.ui.resources.menu_rename
import ymusic.ui.resources.menu_show_in_folder
import ymusic.ui.resources.n_songs
import ymusic.ui.resources.playlist_create_confirm
import ymusic.ui.resources.playlist_create_title
import ymusic.ui.resources.playlist_delete_message
import ymusic.ui.resources.playlist_delete_title
import ymusic.ui.resources.playlist_name_hint
import ymusic.ui.resources.playlist_new
import ymusic.ui.resources.playlist_recent_play
import ymusic.ui.resources.playlist_recent_play_desc
import ymusic.ui.resources.playlist_rename_confirm
import ymusic.ui.resources.playlist_rename_title
import ymusic.ui.resources.playlist_top_tracks
import ymusic.ui.resources.playlist_top_tracks_desc
import ymusic.ui.resources.queue_last
import ymusic.ui.resources.queue_next
import ymusic.ui.resources.sort_date_added
import ymusic.ui.resources.sort_date_created
import ymusic.ui.resources.sort_last_added
import ymusic.ui.resources.sort_name
import ymusic.ui.resources.sort_song_count
import ymusic.ui.resources.sort_type
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

/** A song's "Show in folder" action, provided by [LibraryScreen] over its tabs — jumps to the
 *  Folders tab and opens the song's bucket. Null outside the Library tabs (e.g. the search screen),
 *  which hides the menu item. */
internal val LocalLibraryReveal = compositionLocalOf<((MediaStoreItem) -> Unit)?> { null }

/** A song's "Remove from disk" REQUEST — opens [LibraryScreen]'s confirm dialog; nothing is
 *  deleted until the user confirms there. Null (no shell delete hook) hides the menu item. */
internal val LocalLibraryDelete = compositionLocalOf<((MediaStoreItem) -> Unit)?> { null }

/** A song's "Add to playlist" REQUEST — opens [LibraryScreen]'s playlist picker for this song.
 *  Provided over the Library tabs; null elsewhere hides the menu item. */
internal val LocalAddToPlaylist = compositionLocalOf<((MediaStoreItem) -> Unit)?> { null }

/** A song's "Remove from playlist" action — provided only inside an opened user playlist, so the menu
 *  item shows there and nowhere else. */
internal val LocalPlaylistRemove = compositionLocalOf<((MediaStoreItem) -> Unit)?> { null }

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
                // childBuckets(null) (roots only, indexed) — NOT allBuckets(), which loads every
                // bucket row (145k on a Downloads scan) just to test non-empty and stalls the UI.
                LibraryContent(store.allItems(), store.childBuckets(null).isNotEmpty())
            }
    }.collectAsState(initial = null)

    // App-level so a scan survives leaving the Library screen and still notifies on completion.
    val scanController = LocalScanController.current

    val loaded = content
    when {
        loaded == null -> CenteredBox { CircularProgressIndicator() }
        loaded.isEmpty -> EmptyLibrary(graph.scanLibraryFolder, scanController)
        else -> {
            // Download location (a Library folder, root or nested) — the Folders tab marks it and its
            // menu re-designates it. Only meaningful when the shell can download.
            val dlScope = rememberCoroutineScope()
            val downloadLocationRaw by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()
            val downloadLocation: String? = downloadLocationRaw.takeIf { graph.downloads != null && it.isNotBlank() }
            val onToggleDownloadLocation: ((MediaStoreBucket) -> Unit)? = if (graph.downloads == null) {
                null
            } else {
                { bucket ->
                    val target = bucket.uri.toString()
                    dlScope.launch {
                        graph.settings.put(AppSettings.downloadLocation, if (downloadLocationRaw == target) "" else target)
                    }
                }
            }
            var tab by rememberSaveable { mutableStateOf(LibraryTab.Folders) }
            // A song's "Show in folder" jump: the bucket to open, consumed by the Folders tab once
            // it composes, then cleared.
            var revealBucket by remember { mutableStateOf<Long?>(null) }
            val reveal: (MediaStoreItem) -> Unit = { item ->
                item.bucketId?.let { revealBucket = it }
                tab = LibraryTab.Folders
            }
            // A cross-section reveal (Downloads → "Reveal in Library"): open the requested folder here
            // and clear the request so it fires once.
            val folderReveal = LocalLibraryFolderReveal.current
            LaunchedEffect(folderReveal?.value) {
                val bucketId = folderReveal?.value ?: return@LaunchedEffect
                revealBucket = bucketId
                tab = LibraryTab.Folders
                folderReveal.value = null
            }
            // "Remove from disk": the menu only REQUESTS; the deletion runs from the dialog's
            // confirm, and the store row goes only after the file did (itemsChanges reloads the UI).
            var confirmDelete by remember { mutableStateOf<MediaStoreItem?>(null) }
            val requestDelete: ((MediaStoreItem) -> Unit)? =
                graph.deleteFromDisk?.let { { item -> confirmDelete = item } }
            confirmDelete?.let { doomed ->
                DeleteFromDiskDialog(
                    doomed,
                    onDismiss = { confirmDelete = null },
                    delete = {
                        graph.deleteFromDisk!!.invoke(doomed)
                        store.deleteItem(doomed.id, hard = true)
                    },
                )
            }
            // "Add to playlist": the menu only opens the picker; the store mutation runs from there.
            var addToPlaylist by remember { mutableStateOf<MediaStoreItem?>(null) }
            addToPlaylist?.let { song ->
                AddToPlaylistDialog(store, song, onDismiss = { addToPlaylist = null })
            }
            // Keeps each tab's saveable state (folder path, scroll) while another tab is showing.
            val tabStateHolder = rememberSaveableStateHolder()
            // Live swipe progress (−1..1) so the underline tracks the drag, not just the commit.
            var tabDrag by remember { mutableFloatStateOf(0f) }
            Column(Modifier.fillMaxSize()) {
                // The tab row is pinned under the app bar and elevates with it (one continuous
                // surface); each tab's sort/breadcrumb scroll away with the content below.
                ElevatedBarColumn {
                    LibraryTabRow(tab, tabDrag) { tab = it }
                    HorizontalDivider(color = DesignColors.Divider)
                }
                CompositionLocalProvider(
                    LocalLibraryReveal provides reveal,
                    LocalLibraryDelete provides requestDelete,
                    LocalAddToPlaylist provides { song -> addToPlaylist = song },
                ) {
                    val openDrawer = LocalOpenNavDrawer.current
                    SwipeTabSwitcher(
                        tab,
                        LibraryTab.entries,
                        onSelect = { tab = it },
                        onDragProgress = { tabDrag = it },
                        leadingEdgeReserve = NavDrawerEdgeReserve,
                        onLeadingEdgeSwipe = openDrawer,
                    ) { current ->
                        tabStateHolder.SaveableStateProvider(current.name) {
                            when (current) {
                                LibraryTab.Folders -> FoldersTab(
                                    store,
                                    loaded,
                                    coordinator,
                                    graph.scanLibraryFolder,
                                    graph.rescanLibrary,
                                    scanController,
                                    revealBucket,
                                    onRevealHandled = { revealBucket = null },
                                    downloadLocation = downloadLocation,
                                    onToggleDownloadLocation = onToggleDownloadLocation,
                                )
                                LibraryTab.Playlists -> PlaylistsTab(store, loaded, coordinator)
                                LibraryTab.Songs -> {
                                    val sort = rememberSortState("sort.songs")
                                    SongList(
                                        sort.sort(loaded.songs, songSortOptions),
                                        coordinator,
                                        header = { SortBar(songSortOptions.map { it.label }, sort) },
                                    )
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
    }
}

/** First-open state: nothing indexed yet — just the scan call-to-action. */
@Composable
private fun EmptyLibrary(scan: ScanAction?, controller: ScanController?) {
    CenteredBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(Res.string.library_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scan == null || controller == null) return@Column
            ScanPill(scan, controller, label = stringResource(Res.string.library_scan_folder))
            if (controller.running) CircularProgressIndicator(Modifier.size(22.dp))
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
    scan: ScanAction?,
    rescan: ScanAction?,
    scanController: ScanController?,
    revealBucket: Long?,
    onRevealHandled: () -> Unit,
    downloadLocation: String?,
    onToggleDownloadLocation: ((MediaStoreBucket) -> Unit)?,
) {
    fun isDownloadLocation(bucket: MediaStoreBucket) =
        downloadLocation != null && bucket.uri.toString() == downloadLocation
    // (id, displayName) instead of the bucket itself so the browse position survives the section
    // leaving composition (saveable): coming back from Discover reopens the same folder.
    var path by rememberSaveable(stateSaver = CrumbPathSaver) { mutableStateOf(listOf<Pair<Long, String>>()) }
    var folders by remember { mutableStateOf<List<MediaStoreBucket>?>(null) }
    var items by remember { mutableStateOf<List<MediaStoreItem>>(emptyList()) }
    val folderListState = rememberLazyListState()
    val folderGridState = rememberLazyGridState()

    // "Show in folder": open the requested bucket by walking its ancestor chain up to a root.
    LaunchedEffect(revealBucket) {
        val target = revealBucket ?: return@LaunchedEffect
        val byId = store.allBuckets().associateBy { it.id }
        val chain = mutableListOf<Pair<Long, String>>()
        var id: Long? = target
        while (id != null) {
            val bucket = byId[id] ?: break
            chain.add(0, bucket.id to bucket.displayName)
            id = bucket.parentId
        }
        path = chain
        onRevealHandled()
    }

    LaunchedEffect(path, content) {
        val currentId = path.lastOrNull()?.first
        folders = store.childBuckets(currentId)
        items = store.itemsInBucket(currentId)
    }

    Column(Modifier.fillMaxSize()) {
        val children = folders
        if (children == null) {
            CenteredBox { CircularProgressIndicator() }
            return@Column
        }
        // Excluded folders show but don't open; the three-dot menu removes a root or excludes/includes
        // a subfolder (each cascades in the store, then the change flow reloads this list).
        val openFolder = { bucket: MediaStoreBucket -> if (!bucket.excluded) path = path + (bucket.id to bucket.displayName) }
        val scope = rememberCoroutineScope()
        val onRemove: (MediaStoreBucket) -> Unit = { b -> scope.launch { store.deleteBucket(b.id) } }
        val onExclude: (MediaStoreBucket) -> Unit = { b -> scope.launch { store.setBucketExcluded(b.id, true) } }
        // Excluding deleted the folder's subfolders and tombstoned its items, so re-including clears
        // the flag and then rescans to bring the contents back (the scan skips the folder until the
        // flag is cleared, so order matters).
        val onInclude: (MediaStoreBucket) -> Unit = { b ->
            scope.launch {
                store.setBucketExcluded(b.id, false)
                if (rescan != null && scanController != null) scanController.start(rescan)
            }
        }
        val sort = rememberSortState("sort.folders")
        val sortedFolders = sort.sort(children, folderSortOptions)
        val sortedItems = sort.sort(items, songSortOptions)
        val showScan = path.isEmpty() && scan != null
        val hasContent = children.isNotEmpty() || items.isNotEmpty()
        // The scan/sort toolbar scrolls away with the content (only the app bar and tab row stay
        // pinned). At root the left side holds the scan actions; in a subfolder those are gone, so the
        // breadcrumb takes their place on the same line as the sort control.
        val header: @Composable () -> Unit = {
            if (path.isEmpty()) {
                if (showScan || hasContent) {
                    FoldersToolbar(
                        scan = if (showScan) scan else null,
                        rescan = if (showScan) rescan else null,
                        scanController = scanController,
                        sort = if (hasContent) sort else null,
                        sortLabels = folderSortOptions.map { it.label },
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Breadcrumb(path.map { it.second }, Modifier.weight(1f)) { depth -> path = path.take(depth) }
                    SortControl(folderSortOptions.map { it.label }, sort)
                }
            }
        }
        TrackScroll { folderListState.canScrollBackward || folderGridState.canScrollBackward }
        if (children.isEmpty() && items.isEmpty()) {
            header()
            Text(
                stringResource(Res.string.library_empty_folder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Large screens: folders as an album-card grid (max-width cards), this folder's songs
                // as a wider list below them (see design/final/library.html). Songs are min-width
                // sized — full-width when tight, splitting as the screen grows — and rendered as
                // full-width weighted rows so they stay gap-free whatever the folder column count.
                if (maxWidth >= LibraryGridMinWidth) {
                    val columns = libraryGridColumns(maxWidth)
                    val songsPerRow = librarySongColumns(maxWidth)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        state = folderGridState,
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { header() }
                        items(sortedFolders, key = { "b${it.id}" }) { bucket ->
                            LibraryGridCard(
                                icon = Icons.Default.Folder,
                                artUrl = null,
                                title = bucket.displayName,
                                subtitle = stringResource(Res.string.library_folder),
                                dimmed = bucket.excluded,
                                marked = isDownloadLocation(bucket),
                                menu = folderActions(bucket, onRemove, onExclude, onInclude, isDownloadLocation(bucket), onToggleDownloadLocation),
                            ) { openFolder(bucket) }
                        }
                        // Full-span rows break the flow onto a fresh line below the folders.
                        items(
                            sortedItems.indices.chunked(songsPerRow),
                            key = { sortedItems[it.first()].id },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { group ->
                            Row(Modifier.fillMaxWidth()) {
                                group.forEach { i ->
                                    Box(Modifier.weight(1f)) { SongRow(sortedItems[i], coordinator) { coordinator.playLocal(sortedItems, i) } }
                                }
                                repeat(songsPerRow - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = folderListState) {
                        item { header() }
                        items(sortedFolders, key = { "b${it.id}" }) { bucket ->
                            FolderRow(
                                bucket,
                                folderActions(bucket, onRemove, onExclude, onInclude, isDownloadLocation(bucket), onToggleDownloadLocation),
                                isDownloadLocation = isDownloadLocation(bucket),
                            ) { openFolder(bucket) }
                        }
                        itemsIndexed(sortedItems, key = { _, s -> s.id }) { index, song ->
                            SongRow(song, coordinator) { coordinator.playLocal(sortedItems, index) }
                        }
                    }
                }
            }
        }
    }
}

/** The width at/above which the Library's browse grids appear (below it, a single-column list). The
 *  Library sets its own breakpoint rather than borrowing the Discover video feed's. */
internal val LibraryGridMinWidth = 480.dp

/** The fixed height of a tab's sub-toolbar band — the sort/scan/breadcrumb toolbar and the drill-down
 *  back-row all use it, with their content vertically centered, so any element's center sits at the
 *  same Y whether you're at the list level or the detail level (nothing jumps on drill-in). Fixed
 *  rather than wrap-content because the pill / chip / icon-button children have slightly different
 *  intrinsic heights. */
internal val LibrarySubToolbarHeight = 40.dp

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

// ---- per-tab sort options (label order is shared where two lists sort together) ----

/** Songs, and the songs shown in the Folders tab: name / date added / audio-before-video. */
private val songSortOptions = listOf(
    SortOption<MediaStoreItem>(Res.string.sort_name, compareBy { it.title.lowercase() }),
    SortOption(Res.string.sort_date_added, compareBy { it.createdAt }),
    SortOption(Res.string.sort_type, compareBy({ if (it.videoCodec != null) 1 else 0 }, { it.title.lowercase() })),
)

/** Folders in the Folders tab — Name / date added, sharing the tab's one sort control with the
 *  folder's songs ([songSortOptions]). Its label order is the prefix of [songSortOptions], so the
 *  shared index stays valid for both; folders have no Type distinction, so Type is left off here
 *  (the control never offers an index past date-added). */
private val folderSortOptions = listOf(
    SortOption<MediaStoreBucket>(Res.string.sort_name, compareBy { it.displayName.lowercase() }),
    SortOption(Res.string.sort_date_added, compareBy { it.createdAt }),
)

/** Playlist browse rows (playlist + its song count). */
private val playlistSortOptions = listOf(
    SortOption<Pair<Playlist, Int>>(Res.string.sort_name, compareBy { it.first.name.lowercase() }),
    SortOption(Res.string.sort_date_created, compareBy { it.first.createdAt }),
    SortOption(Res.string.sort_song_count, compareBy { it.second }),
)

/** The songs inside a USER playlist: by when they were added to the playlist (default — matches the
 *  order they were appended in) or by name. The system playlists (Recent play / Top tracks) keep their
 *  intrinsic order and offer no sort. */
private val playlistSongSortOptions = listOf(
    SortOption<PlaylistItemDetail>(Res.string.sort_last_added, compareBy { it.addedAt }),
    SortOption(Res.string.sort_name, compareBy { it.mediaItem.title.lowercase() }),
)

/** Artist / album / genre groups (name + member songs); "date created" = the group's earliest add. */
private val groupSortOptions = listOf(
    SortOption<Pair<String, List<MediaStoreItem>>>(Res.string.sort_name, compareBy { it.first.lowercase() }),
    SortOption(Res.string.sort_date_created, compareBy { it.second.minOfOrNull { m -> m.createdAt } ?: 0L }),
    SortOption(Res.string.sort_song_count, compareBy { it.second.size }),
)

/** Saves the folder browse path as a flat [id, name, id, name, …] list. */
private val CrumbPathSaver = listSaver<List<Pair<Long, String>>, Any>(
    save = { path -> path.flatMap { (id, name) -> listOf(id, name) } },
    restore = { flat -> flat.chunked(2).map { (id, name) -> id as Long to name as String } },
)

/** `Library › root › sub` — every crumb but the last navigates back up to it. */
@Composable
internal fun Breadcrumb(
    path: List<String>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onNavigate: (depth: Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    // Keep the deepest crumb (where you are) visible as the trail grows past the edge.
    LaunchedEffect(path.size) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier
            .horizontalWheelScroll(scrollState)
            .horizontalScroll(scrollState)
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
private fun FolderRow(bucket: MediaStoreBucket, menu: ItemActions, isDownloadLocation: Boolean, onOpen: () -> Unit) {
    val contentAlpha = if (bucket.excluded) 0.4f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !bucket.excluded, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.alpha(contentAlpha)) { LibraryThumb(Icons.Default.Folder, badge = null) }
        Column(Modifier.weight(1f).alpha(contentAlpha)) {
            Text(bucket.displayName, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.library_folder), style = ListSubStyle, modifier = Modifier.padding(top = 4.dp))
        }
        if (isDownloadLocation) {
            Icon(
                Icons.Default.Download,
                contentDescription = stringResource(Res.string.menu_download_location),
                tint = DesignColors.Accent,
                modifier = Modifier.padding(top = 10.dp).size(18.dp),
            )
        }
        MoreMenu(menu, Modifier.padding(top = 2.dp))
    }
}

/** The folder-row overflow menu: a root folder offers Remove (un-add it); a subfolder offers Exclude,
 *  or Include when it's already excluded; any included folder can be toggled as the download location. */
@Composable
private fun folderActions(
    bucket: MediaStoreBucket,
    onRemove: (MediaStoreBucket) -> Unit,
    onExclude: (MediaStoreBucket) -> Unit,
    onInclude: (MediaStoreBucket) -> Unit,
    isDownloadLocation: Boolean,
    onToggleDownloadLocation: ((MediaStoreBucket) -> Unit)?,
): ItemActions {
    val remove = stringResource(Res.string.menu_remove_folder)
    val exclude = stringResource(Res.string.menu_exclude_folder)
    val include = stringResource(Res.string.menu_include_folder)
    val downloadLocation = stringResource(Res.string.menu_download_location)
    return ItemActions(
        buildList {
            when {
                bucket.parentId == null -> add(remove to { onRemove(bucket) })
                bucket.excluded -> add(include to { onInclude(bucket) })
                else -> add(exclude to { onExclude(bucket) })
            }
            if (onToggleDownloadLocation != null && !bucket.excluded) {
                add(downloadLocation to { onToggleDownloadLocation(bucket) })
            }
        },
        checked = if (isDownloadLocation) setOf(downloadLocation) else emptySet(),
    )
}

private val MediaStoreBucket.displayName: String get() = aliasName?.takeIf(String::isNotBlank) ?: name

/** The irreversible-delete gate: names the file, says it cannot be undone, and runs [delete] only
 *  on explicit confirm. A failed delete keeps the dialog open with the error (the row stays — the
 *  store is only touched after the file is gone). */
@Composable
private fun DeleteFromDiskDialog(
    item: MediaStoreItem,
    onDismiss: () -> Unit,
    delete: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(item) { mutableStateOf(false) }
    var error by remember(item) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.delete_from_disk_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.delete_from_disk_message, item.title))
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            delete()
                            onDismiss()
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.delete_from_disk_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
    )
}

// ---- playlists ----

// Sentinel ids for the computed "system" playlists — they aren't rows in the store (see
// media-store MediaItem.sq recentlyPlayed/recentlyAdded/topTracks), so they use negative ids that
// AUTOINCREMENT user playlists (always positive) never collide with.
private const val SMART_RECENT_PLAY = -1L
private const val SMART_TOP_TRACKS = -3L

/** One row in the Playlists list: either a computed system playlist or a user-created one. */
private sealed interface PlaylistEntry {
    val id: Long
}

private data class SmartEntry(
    override val id: Long,
    val labelRes: StringResource,
    val descRes: StringResource,
    val icon: ImageVector,
) : PlaylistEntry

private data class UserEntry(val playlist: Playlist, val count: Int) : PlaylistEntry {
    override val id get() = playlist.id
}

/** The system playlists, pinned above the user playlists in this fixed order. (No "Last added" —
 *  the Songs tab's "Date added" sort already covers it.) */
private val smartPlaylists = listOf(
    SmartEntry(SMART_RECENT_PLAY, Res.string.playlist_recent_play, Res.string.playlist_recent_play_desc, Icons.Default.History),
    SmartEntry(SMART_TOP_TRACKS, Res.string.playlist_top_tracks, Res.string.playlist_top_tracks_desc, Icons.Default.Whatshot),
)

@Composable
private fun PlaylistsTab(store: BetterMediaStore, content: LibraryContent, coordinator: PlaybackCoordinator) {
    // (id, name) — saveable, so the opened playlist survives the tab leaving composition. Negative ids
    // are the system playlists; the name is the resolved display title.
    var selected by rememberSaveable(stateSaver = OpenPlaylistSaver) { mutableStateOf<Pair<Long, String>?>(null) }
    var userLists by remember { mutableStateOf<List<Pair<Playlist, Int>>?>(null) }
    // A system playlist's songs (intrinsic order, no sort); a user playlist's ordered membership,
    // kept as PlaylistItemDetail so it can be sorted by addedAt as well as by title.
    var systemSongs by remember { mutableStateOf<List<MediaStoreItem>?>(null) }
    var userItems by remember { mutableStateOf<List<PlaylistItemDetail>?>(null) }
    val playlistSongSort = rememberSortState("sort.playlist.songs")
    // Create/rename/delete don't emit itemsChanges, so the outer content tick won't reload the list —
    // bump this after any playlist mutation to reload playlists() + counts.
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // One list + one grid for BOTH levels (list of playlists / a playlist's songs). Because the same
    // container is reused across the drill-in, only the header + rows change — the list never remounts,
    // so opening a playlist doesn't jump. This mirrors how the Folders tab reuses one list root→subfolder.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(content, refreshTick) {
        userLists = store.playlists().map { it to store.playlistItems(it.id).size }
    }
    // The opened playlist's songs. System lists are one-shot queries (re-run when the library changes);
    // a user playlist reads its live membership so add/remove/reorder reflect at once. We DON'T clear
    // the previous list first — it stays until the new one lands (Folders-style), so drilling in
    // doesn't flash empty then reflow.
    LaunchedEffect(selected?.first, content) {
        val id = selected?.first ?: return@LaunchedEffect
        when (id) {
            SMART_RECENT_PLAY -> systemSongs = store.recentlyPlayed()
            SMART_TOP_TRACKS -> systemSongs = store.topTracks()
            else -> store.playlistChanges(id).collect { userItems = store.playlistItemDetails(id) }
        }
    }

    // Create / rename / delete dialogs (user playlists only).
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    if (showCreate) {
        PlaylistNameDialog(
            title = stringResource(Res.string.playlist_create_title),
            confirmLabel = stringResource(Res.string.playlist_create_confirm),
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name -> store.createPlaylist(name); refreshTick++ },
        )
    }
    renaming?.let { pl ->
        PlaylistNameDialog(
            title = stringResource(Res.string.playlist_rename_title),
            confirmLabel = stringResource(Res.string.playlist_rename_confirm),
            initial = pl.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> store.renamePlaylist(pl.id, name); refreshTick++ },
        )
    }
    deleting?.let { pl ->
        DeletePlaylistDialog(
            pl.name,
            onDismiss = { deleting = null },
            delete = {
                store.deletePlaylist(pl.id)
                if (selected?.first == pl.id) selected = null
                refreshTick++
            },
        )
    }

    val open = selected
    val isUser = open != null && open.first > 0
    val sort = rememberSortState("sort.playlists")
    // The first item is always the header — the list-level toolbar, or the drill-down back-row. Both
    // use the same row padding/height, so it stays put across the drill-in. A user playlist carries a
    // sort control at the same end position as the list-level toolbar's (system playlists carry none).
    val header: @Composable () -> Unit = {
        if (open == null) {
            PlaylistsToolbar(sort) { showCreate = true }
        } else {
            DrillDownHeader(
                open.second,
                onBack = { selected = null },
                trailing = if (isUser) ({ SortControl(playlistSongSortOptions.map { it.label }, playlistSongSort) }) else null,
            )
        }
    }
    // Level content: system playlists (shown at once) + user playlists at the list level; the opened
    // playlist's songs at the detail level (user playlists sorted by the detail sort control).
    val entries: List<PlaylistEntry> =
        if (open != null) {
            emptyList()
        } else {
            smartPlaylists + (userLists?.let { sort.sort(it, playlistSortOptions).map { p -> UserEntry(p.first, p.second) } } ?: emptyList())
        }
    val songs: List<MediaStoreItem> = when {
        open == null -> emptyList()
        isUser -> playlistSongSort.sort(userItems.orEmpty(), playlistSongSortOptions).map { it.mediaItem }
        else -> systemSongs.orEmpty()
    }
    // A user playlist offers "Remove from playlist" on each song row; the list level / system lists don't.
    val removeFromPlaylist: ((MediaStoreItem) -> Unit)? = remember(open?.first) {
        val id = open?.first
        if (id != null && id > 0) ({ song: MediaStoreItem -> scope.launch { store.removeFromPlaylist(id, song.playlistCode) }; Unit }) else null
    }

    TrackScroll { listState.canScrollBackward || gridState.canScrollBackward }
    CompositionLocalProvider(LocalPlaylistRemove provides removeFromPlaylist) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= LibraryGridMinWidth) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(libraryGridColumns(maxWidth)),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    state = gridState,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { header() }
                    if (open == null) {
                        items(entries, key = { it.id }) { entry ->
                            PlaylistCard(entry, onOpen = { selected = it }, onRename = { renaming = it }, onDelete = { deleting = it })
                        }
                    } else {
                        val perRow = librarySongColumns(maxWidth)
                        items(
                            songs.indices.chunked(perRow),
                            key = { songs[it.first()].id },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { group ->
                            Row(Modifier.fillMaxWidth()) {
                                group.forEach { i -> Box(Modifier.weight(1f)) { SongRow(songs[i], coordinator) { coordinator.playLocal(songs, i) } } }
                                repeat(perRow - group.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item { header() }
                    if (open == null) {
                        items(entries, key = { it.id }) { entry ->
                            PlaylistRow(entry, onOpen = { selected = it }, onRename = { renaming = it }, onDelete = { deleting = it })
                        }
                    } else {
                        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                            SongRow(song, coordinator) { coordinator.playLocal(songs, i) }
                        }
                    }
                }
            }
        }
    }
}

/** A playlist entry as a list row (phone widths). */
@Composable
private fun PlaylistRow(
    entry: PlaylistEntry,
    onOpen: (Pair<Long, String>) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val (title, sub, icon) = playlistDisplay(entry)
    val actions = (entry as? UserEntry)?.let {
        playlistActions(it.playlist, onRename = { onRename(it.playlist) }, onDelete = { onDelete(it.playlist) })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.id to title) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LibraryThumb(icon, badge = (entry as? UserEntry)?.count?.toString())
        Column(Modifier.weight(1f)) {
            Text(title, style = ListTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, style = ListSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        if (actions != null && actions.entries.isNotEmpty()) MoreMenu(actions, Modifier.padding(top = 2.dp))
    }
}

/** A playlist entry as a grid card (wide widths). */
@Composable
private fun PlaylistCard(
    entry: PlaylistEntry,
    onOpen: (Pair<Long, String>) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    val (title, sub, icon) = playlistDisplay(entry)
    val actions = (entry as? UserEntry)?.let {
        playlistActions(it.playlist, onRename = { onRename(it.playlist) }, onDelete = { onDelete(it.playlist) })
    }?.takeIf { it.entries.isNotEmpty() }
    LibraryGridCard(icon = icon, artUrl = null, title = title, subtitle = sub, menu = actions) { onOpen(entry.id to title) }
}

/** A playlist row's (title, subtitle, icon): a system playlist shows its description, a user playlist
 *  its song count. */
@Composable
private fun playlistDisplay(entry: PlaylistEntry): Triple<String, String, ImageVector> = when (entry) {
    is SmartEntry -> Triple(stringResource(entry.labelRes), stringResource(entry.descRes), entry.icon)
    is UserEntry -> Triple(
        entry.playlist.name,
        pluralStringResource(Res.plurals.n_songs, entry.count, entry.count),
        Icons.AutoMirrored.Filled.PlaylistPlay,
    )
}

/** The user-playlist overflow menu — rename / delete. Empty for a system playlist (no menu shown). */
@Composable
private fun playlistActions(playlist: Playlist, onRename: () -> Unit, onDelete: () -> Unit): ItemActions {
    val rename = stringResource(Res.string.menu_rename)
    val delete = stringResource(Res.string.menu_delete_playlist)
    return ItemActions(listOf(rename to onRename, delete to onDelete))
}

/** The Playlists toolbar: the "+ New playlist" pill on the left, the sort control on the right. */
@Composable
private fun PlaylistsToolbar(sort: SortState, onCreate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(LibrarySubToolbarHeight).padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryChip(stringResource(Res.string.playlist_new), selected = false, onClick = onCreate)
        Spacer(Modifier.weight(1f))
        SortControl(playlistSortOptions.map { it.label }, sort)
    }
}

/** Create / rename text-input dialog. [onConfirm] runs the store mutation; the dialog closes after it
 *  (or on cancel). Confirm is disabled while the field is blank or the mutation is running. */
@Composable
private fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.playlist_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onConfirm(name.trim())
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
    )
}

/** Delete-playlist confirm. Removes the playlist (its membership cascades); the songs stay in the library. */
@Composable
private fun DeletePlaylistDialog(name: String, onDismiss: () -> Unit, delete: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.playlist_delete_title)) },
        text = { Text(stringResource(Res.string.playlist_delete_message, name)) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            delete()
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.delete_from_disk_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
    )
}

/** The "Add to playlist" picker: the user's playlists plus a "New playlist…" row. Picking a playlist
 *  appends [song] and closes; "New playlist…" swaps to the name dialog, then creates + appends. */
@Composable
private fun AddToPlaylistDialog(store: BetterMediaStore, song: MediaStoreItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lists = store.playlists() }

    if (creating) {
        PlaylistNameDialog(
            title = stringResource(Res.string.playlist_create_title),
            confirmLabel = stringResource(Res.string.playlist_create_confirm),
            initial = "",
            onDismiss = onDismiss,
            onConfirm = { name ->
                val pl = store.createPlaylist(name)
                store.addToPlaylist(pl.id, listOf(song))
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cd_add_to_playlist)) },
        text = {
            val loaded = lists
            if (loaded == null) {
                CircularProgressIndicator()
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    PlaylistPickRow(Icons.Default.Add, stringResource(Res.string.add_to_playlist_new)) { creating = true }
                    loaded.forEach { pl ->
                        PlaylistPickRow(Icons.AutoMirrored.Filled.PlaylistPlay, pl.name) {
                            scope.launch {
                                store.addToPlaylist(pl.id, listOf(song))
                                onDismiss()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) } },
    )
}

@Composable
private fun PlaylistPickRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = DesignColors.InkSoft)
        Text(label, style = ListTitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** The Library's tab row — the shared [UnderlineTabRow] (2dp sliding underline) with text labels. */
@Composable
private fun LibraryTabRow(selected: LibraryTab, dragProgress: Float, onSelect: (LibraryTab) -> Unit) {
    UnderlineTabRow(selected, LibraryTab.entries, dragProgress, onSelect) { t, active ->
        TabLabel(stringResource(t.label), active)
    }
}

/** The outlined pill that starts a scan; while one runs it shows the live scanned count off the
 *  app-level [controller], so the count keeps updating even after the pill leaves composition. */
@Composable
private fun ScanPill(scan: ScanAction, controller: ScanController, label: String) {
    val count = controller.scanned
    Text(
        when {
            controller.running && count != null -> stringResource(Res.string.library_scanning_count, count)
            controller.running -> stringResource(Res.string.library_scanning)
            else -> label
        },
        style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = DesignColors.Ink),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(enabled = !controller.running) { controller.start(scan) }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** The Folders tab toolbar: the scan actions (add-folder pill + rescan button, root level only) on
 *  the left, the sort control on the right. Any side may be absent (a subfolder has no scan; an
 *  empty root has no sort; no scan controller means no scan controls). */
@Composable
private fun FoldersToolbar(
    scan: ScanAction?,
    rescan: ScanAction?,
    scanController: ScanController?,
    sort: SortState?,
    sortLabels: List<StringResource>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scan != null && scanController != null) {
            ScanPill(scan, scanController, label = stringResource(Res.string.library_add_folder))
            if (rescan != null) {
                IconButton(
                    onClick = { scanController.start(rescan) },
                    enabled = !scanController.running,
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
            if (scanController.running) CircularProgressIndicator(Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        if (sort != null) SortControl(sortLabels, sort)
    }
}

@Composable
internal fun SongList(
    songs: List<MediaStoreItem>,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    TrackScroll { listState.canScrollBackward || gridState.canScrollBackward }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Same min-width sizing as the Folders tab's songs: as many columns as keep each song
        // ≥ LibrarySongMinWidth, dropping to a single full-width column when that's all that fits.
        if (maxWidth >= LibraryGridMinWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(librarySongColumns(maxWidth)),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
                items(songs.size, key = { songs[it].id }) { index ->
                    SongRow(songs[index], coordinator) { coordinator.playLocal(songs, index) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (header != null) item { header() }
                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                    SongRow(song, coordinator) { coordinator.playLocal(songs, index) }
                }
            }
        }
    }
}

@Composable
internal fun SongRow(song: MediaStoreItem, coordinator: PlaybackCoordinator? = null, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
        if (coordinator != null) MoreMenu(songActions(song, coordinator), Modifier.padding(top = 2.dp))
    }
}

/** The song-row overflow menu: play this one, queue it next / last, and (in the Library tabs, for a
 *  song that lives in a folder) reveal it in the Folders tab. */
@Composable
private fun songActions(song: MediaStoreItem, coordinator: PlaybackCoordinator): ItemActions {
    val play = stringResource(Res.string.menu_play)
    val next = stringResource(Res.string.queue_next)
    val last = stringResource(Res.string.queue_last)
    val addToPlaylistLabel = stringResource(Res.string.cd_add_to_playlist)
    val removeFromPlaylistLabel = stringResource(Res.string.menu_remove_from_playlist)
    val showInFolder = stringResource(Res.string.menu_show_in_folder)
    val removeFromDisk = stringResource(Res.string.menu_remove_from_disk)
    val reveal = LocalLibraryReveal.current
    val requestDelete = LocalLibraryDelete.current
    val addToPlaylist = LocalAddToPlaylist.current
    val removeFromPlaylist = LocalPlaylistRemove.current
    return ItemActions(
        buildList {
            add(play to { coordinator.playLocalSingle(song) })
            add(next to { coordinator.queueLocalNext(song) })
            add(last to { coordinator.queueLocalLast(song) })
            if (addToPlaylist != null) add(addToPlaylistLabel to { addToPlaylist(song) })
            if (removeFromPlaylist != null) add(removeFromPlaylistLabel to { removeFromPlaylist(song) })
            if (reveal != null && song.bucketId != null) add(showInFolder to { reveal(song) })
            if (requestDelete != null) add(removeFromDisk to { requestDelete(song) })
        },
    )
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
        val sort = rememberSortState("sort.group")
        LibraryBrowseLevel(
            entries = sort.sort(grouped, groupSortOptions),
            key = { it.first },
            sortBar = { SortBar(groupSortOptions.map { it.label }, sort) },
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
        SongList(
            grouped.firstOrNull { it.first == selected }?.second.orEmpty(),
            coordinator,
            header = { DrillDownHeader(selected, onBack = { group = null }) },
        )
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
    sortBar: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    TrackScroll { listState.canScrollBackward || gridState.canScrollBackward }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= LibraryGridMinWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(libraryGridColumns(maxWidth)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                state = gridState,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { sortBar() }
                items(entries, key = key) { entry -> card(entry) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item { sortBar() }
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
    dimmed: Boolean = false,
    marked: Boolean = false,
    menu: ItemActions? = null,
    onClick: () -> Unit,
) {
    // Dim the art + labels for an excluded folder, but keep any overflow menu at full strength so it
    // stays legible and tappable (that's how the folder gets re-included).
    val contentAlpha = if (dimmed) 0.4f else 1f
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !dimmed, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(contentAlpha)
                    .background(DesignColors.Placeholder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(28.dp), tint = DesignColors.PlaceholderIcon)
                ArtImage(artUrl, RoundedCornerShape(10.dp))
            }
            if (marked) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(Res.string.menu_download_location),
                    tint = DesignColors.Accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
                )
            }
            if (menu != null) MoreMenu(menu, Modifier.align(Alignment.TopEnd))
        }
        Text(
            title,
            style = GridTitleStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).alpha(contentAlpha),
        )
        Text(subtitle, style = GridSubStyle, maxLines = 1, modifier = Modifier.padding(top = 3.dp).alpha(contentAlpha))
    }
}

/** The back-row heading a drill-down (playlist / artist / album / genre / search result). Uses the
 *  exact row padding of a tab's sort toolbar ([PlaylistsToolbar]/[FoldersToolbar]) — same height, same
 *  insets — so drilling in from the list level doesn't shift the content. Passed as [SongList]'s
 *  header so it scrolls with the list rather than pinning above it. */
@Composable
internal fun DrillDownHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        // The 21dp arrow is centred in a 32dp button, so it sits ~5.5dp in from the button's edge.
        // Pull the row's start in by that much (16 − 5.5 ≈ 11) so the arrow GLYPH lines up with the
        // list items' 16dp content inset; the button's larger tap area just reaches a little further left.
        modifier = Modifier.fillMaxWidth().height(LibrarySubToolbarHeight).padding(start = 11.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(Res.string.cd_back),
                Modifier.size(21.dp),
                tint = DesignColors.InkSoft,
            )
        }
        Text(
            title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fills the gap so [trailing] (a sort control) sits at the end padding — the same right
            // edge as the list-level toolbar's sort control, so it doesn't shift on drill-in.
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
internal fun LibraryThumb(icon: ImageVector, badge: String?, artUrl: String? = null, corner: Dp = 10.dp, badgeColor: Color = DesignColors.BadgeInk) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(DesignColors.Placeholder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = DesignColors.PlaceholderIcon)
        ArtImage(artUrl, shape)
        badge?.let {
            Text(
                it,
                style = TextStyle(fontSize = 9.sp, color = Color.White),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(badgeColor, RoundedCornerShape(3.dp))
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
