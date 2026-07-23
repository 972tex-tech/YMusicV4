package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreItem
import ymusic.ui.resources.Res
import ymusic.ui.resources.library_search_hint
import ymusic.ui.resources.library_search_no_matches
import ymusic.ui.resources.library_search_online
import ymusic.ui.resources.library_tab_albums
import ymusic.ui.resources.library_tab_artists
import ymusic.ui.resources.library_tab_songs
import ymusic.ui.resources.n_songs
import ymusic.ui.resources.unknown_album
import ymusic.ui.resources.unknown_artist

/**
 * Library search's retained state: the query, the open artist/album drill-down and the item
 * snapshot it filters (kept fresh over the store's change flow). A layer object, like
 * [SearchSession] — the flow differs (local filter-as-you-type, no suggestions, no paging).
 */
class LibrarySearchSession(store: BetterMediaStore, scope: CoroutineScope) {
    var field by mutableStateOf(TextFieldValue(""))

    /** Open artist/album drill-down: header title + its songs. Back clears it before popping. */
    var drill by mutableStateOf<Pair<String, List<MediaStoreItem>>?>(null)

    var all by mutableStateOf<List<MediaStoreItem>>(emptyList())
        private set

    init {
        scope.launch {
            store.itemsChanges().onStart { emit(Unit) }.conflate().collect { all = store.allItems() }
        }
    }
}

/**
 * The local-files search flow (mirrors the legacy app's Library search): type-to-filter over the
 * library — no suggestions, no submit round-trip — with a "Search "q" online" hand-off row on top,
 * then Songs / Artists / Albums matches. Artist/album taps drill into their songs in place.
 */
@Composable
fun LibrarySearchScreen(
    session: LibrarySearchSession,
    coordinator: PlaybackCoordinator,
    onBack: () -> Unit,
    onSearchOnline: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchInputBar(
            value = session.field,
            onValue = {
                session.field = it
                session.drill = null
            },
            onSubmit = {},
            onBack = { if (session.drill != null) session.drill = null else onBack() },
            autoFocus = session.drill == null,
        )
        val drill = session.drill
        val query = session.field.text.trim()
        when {
            drill != null -> Column(Modifier.fillMaxSize()) {
                DrillDownHeader(drill.first) { session.drill = null }
                SongList(drill.second, coordinator)
            }
            query.isEmpty() -> CenteredBox {
                Text(stringResource(Res.string.library_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val songs = session.all.filter { it.title.contains(query, ignoreCase = true) }
                val unknownArtist = stringResource(Res.string.unknown_artist)
                val unknownAlbum = stringResource(Res.string.unknown_album)
                val artists = groupMatches(session.all, query, unknownArtist) { it.artist }
                val albums = groupMatches(session.all, query, unknownAlbum) { it.album }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(Res.string.library_search_online, query),
                            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSearchOnline(query) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (songs.isNotEmpty()) {
                        item { SectionHeader(YtSectionHeader(title = stringResource(Res.string.library_tab_songs), count = "${songs.size}")) }
                        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                            SongRow(song) { coordinator.playLocal(songs, index) }
                        }
                    }
                    groupSection(Res.string.library_tab_artists, Icons.Default.Person, artists, session)
                    groupSection(Res.string.library_tab_albums, Icons.Default.Album, albums, session)
                    if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty()) {
                        item {
                            Text(
                                stringResource(Res.string.library_search_no_matches),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Groups whose display name matches the query, sorted by name. */
private fun groupMatches(
    all: List<MediaStoreItem>,
    query: String,
    unknownLabel: String,
    keyOf: (MediaStoreItem) -> String?,
): List<Pair<String, List<MediaStoreItem>>> =
    all.groupBy { keyOf(it)?.takeIf(String::isNotBlank) ?: unknownLabel }
        .filterKeys { it.contains(query, ignoreCase = true) }
        .toList()
        .sortedBy { (name, _) -> name.lowercase() }

private fun androidx.compose.foundation.lazy.LazyListScope.groupSection(
    title: org.jetbrains.compose.resources.StringResource,
    icon: ImageVector,
    groups: List<Pair<String, List<MediaStoreItem>>>,
    session: LibrarySearchSession,
) {
    if (groups.isEmpty()) return
    item { SectionHeader(YtSectionHeader(title = stringResource(title), count = "${groups.size}")) }
    itemsIndexed(groups, key = { _, (name, _) -> "$title-$name" }) { _, (name, members) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { session.drill = name to members }
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
    }
}
