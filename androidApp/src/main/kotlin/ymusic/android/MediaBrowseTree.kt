package ymusic.android

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtFeedPage
import ymusic.dynamicengine.yt.extractHomeFeed
import ymusic.dynamicengine.yt.extractMusicCharts
import ymusic.dynamicengine.yt.extractMusicExplore
import ymusic.dynamicengine.yt.extractMusicHome
import ymusic.dynamicengine.yt.extractMusicNewReleases
import ymusic.dynamicengine.yt.extractPlaylistDetail
import ymusic.dynamicengine.yt.extractTrendingMusicFeed
import ymusic.dynamicengine.yt.search
import ymusic.mediastore.model.MediaStoreItem
import ymusic.player.PlayableItem
import ymusic.ui.AppGraph
import ymusic.ui.localArtUrl
import ymusic.ui.toLocalMedia
import ymusic.ui.toYtMedia
import ymusic.ui.youtubeThumbUrl

/**
 * The Android Auto browse hierarchy over the [AppGraph] — three root tabs (the host renders root
 * children as tabs, four max):
 *
 *  - **Library** → Songs / Playlists (incl. the computed Recent-play & Top-tracks lists), straight
 *    from the local media store: always available, offline and logged-out.
 *  - **Discover** → Trending / Home, the `yt/…` engine feeds (anonymous Home is a nudge page — the
 *    folder simply comes back empty, matching the phone app's empty state).
 *  - **YT Music** → Home / Explore / New releases / Charts, the `ytmusic/…` engine feeds.
 *
 * Feed shelves flatten into ONE list per folder, each item tagged with its shelf title via the
 * group-title content style, so playable content sits one level down instead of two. Engine
 * playlist/album entries are browsable nodes whose mediaId carries the engine-composed app link
 * VERBATIM (the engine-url contract); their children come from [extractPlaylistDetail].
 *
 * Every yt item served is remembered in [ytItems] (bounded, insertion-evicting) so a later
 * play request can rebuild the full [YtFeedItem] — the controller round-trips only the mediaId.
 */
class MediaBrowseTree(private val context: Context, private val graph: AppGraph) {

    private val ytItems = object : LinkedHashMap<String, YtFeedItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YtFeedItem>?) = size > YT_CACHE_MAX
    }

    /** The last full list served for a local container — play context for [localContext]. */
    private var lastLocal: Pair<String, List<MediaStoreItem>>? = null

    fun rootItem(): MediaItem = browsable(ROOT, "YMusic", style = null)

    /** The remembered feed item behind a "yt:" playable id, for queue building. */
    @Synchronized
    fun ytItem(videoId: String): YtFeedItem? = ytItems[videoId]

    /** The local songs list [containerId] last served (or re-queried), for context queueing. */
    suspend fun localContext(containerId: String): List<MediaStoreItem> {
        lastLocal?.let { (id, songs) -> if (id == containerId) return songs }
        return localSongs(containerId)
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val all = when {
            parentId == ROOT -> listOf(
                browsable(TAB_LIBRARY, context.getString(R.string.browse_tab_library)),
                browsable(TAB_DISCOVER, context.getString(R.string.browse_tab_discover)),
                browsable(TAB_YTMUSIC, context.getString(R.string.browse_tab_ytmusic)),
            )
            parentId == TAB_LIBRARY -> listOf(
                browsable(LIB_SONGS, context.getString(R.string.browse_library_songs)),
                browsable(LIB_PLAYLISTS, context.getString(R.string.browse_library_playlists)),
            )
            parentId == TAB_DISCOVER -> listOf(
                browsable(DISC_TRENDING, context.getString(R.string.browse_discover_trending)),
                browsable(DISC_HOME, context.getString(R.string.browse_discover_home)),
            )
            parentId == TAB_YTMUSIC -> listOf(
                browsable(YTM_HOME, context.getString(R.string.browse_ytmusic_home)),
                browsable(YTM_EXPLORE, context.getString(R.string.browse_ytmusic_explore)),
                browsable(YTM_NEW, context.getString(R.string.browse_ytmusic_new)),
                browsable(YTM_CHARTS, context.getString(R.string.browse_ytmusic_charts)),
            )
            parentId == LIB_PLAYLISTS -> localPlaylists()
            parentId == LIB_SONGS || parentId.startsWith(LOCAL_LIST_PREFIX) ->
                localSongs(parentId).also { lastLocal = parentId to it }
                    .map { song -> song.toMediaItem(parentId) }
            parentId == DISC_TRENDING -> feedChildren(graph.engines.extractTrendingMusicFeed())
            parentId == DISC_HOME -> feedChildren(graph.engines.extractHomeFeed())
            parentId == YTM_HOME -> feedChildren(graph.engines.extractMusicHome())
            parentId == YTM_EXPLORE -> feedChildren(graph.engines.extractMusicExplore())
            parentId == YTM_NEW -> feedChildren(graph.engines.extractMusicNewReleases())
            parentId == YTM_CHARTS -> feedChildren(graph.engines.extractMusicCharts())
            parentId.startsWith(YT_LIST_PREFIX) ->
                feedChildren(graph.engines.extractPlaylistDetail(parentId.removePrefix(YT_LIST_PREFIX)))
            else -> emptyList()
        }
        if (pageSize <= 0 || page < 0) return all
        return all.drop(page * pageSize).take(pageSize)
    }

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> rootItem()
        mediaId.startsWith(YT_SONG_PREFIX) -> {
            val videoId = mediaId.removePrefix(YT_SONG_PREFIX)
            ytItem(videoId)?.toMediaItem(groupTitle = null)
        }
        mediaId.startsWith(LOCAL_SONG_PREFIX) -> {
            val (containerId, rowId) = parseLocalSongId(mediaId) ?: return null
            graph.mediaStore?.findItem(rowId)?.toMediaItem(containerId)
        }
        else -> null
    }

    /** Engine search, for the Auto search surface — playable videos only, shelf titles as groups. */
    suspend fun searchItems(query: String): List<MediaItem> = feedChildren(graph.engines.search(query = query))

    /** A controller-supplied [MediaItem] (one this tree built — mediaIds round-trip) back as a
     *  facade queue item; null for ids that aren't playable rows. The yt path prefers the
     *  remembered feed item and falls back to the metadata the MediaItem itself carries (the
     *  artworkUri is our wrapped content:// form — unwrap it back to the raw url). */
    suspend fun toPlayableItem(mediaItem: MediaItem): PlayableItem? {
        val id = mediaItem.mediaId
        return when {
            id.startsWith(YT_SONG_PREFIX) -> {
                val videoId = id.removePrefix(YT_SONG_PREFIX)
                ytItem(videoId)?.toYtMedia() ?: PlayableItem.YtMedia(
                    videoId = videoId,
                    title = mediaItem.mediaMetadata.title?.toString(),
                    artist = mediaItem.mediaMetadata.artist?.toString(),
                    thumbnailUrl = mediaItem.mediaMetadata.artworkUri?.getQueryParameter(ARTWORK_URI_PARAM),
                )
            }
            id.startsWith(LOCAL_SONG_PREFIX) -> {
                val (_, rowId) = parseLocalSongId(id) ?: return null
                graph.mediaStore?.findItem(rowId)?.toLocalMedia()
            }
            else -> null
        }
    }

    // ---- local ----

    private suspend fun localPlaylists(): List<MediaItem> {
        val store = graph.mediaStore ?: return emptyList()
        val computed = listOf(
            browsable(
                LOCAL_LIST_PREFIX + LIST_RECENT,
                context.getString(R.string.browse_playlist_recent),
                group = context.getString(R.string.browse_library_playlists),
            ),
            browsable(
                LOCAL_LIST_PREFIX + LIST_TOP,
                context.getString(R.string.browse_playlist_top),
                group = context.getString(R.string.browse_library_playlists),
            ),
        )
        val user = store.playlists().map { pl ->
            browsable(LOCAL_LIST_PREFIX + pl.id, pl.name, group = context.getString(R.string.browse_library_playlists))
        }
        return computed + user
    }

    private suspend fun localSongs(containerId: String): List<MediaStoreItem> {
        val store = graph.mediaStore ?: return emptyList()
        return when (val list = containerId.removePrefix(LOCAL_LIST_PREFIX)) {
            LIB_SONGS -> store.allItems()
            LIST_RECENT -> store.recentlyPlayed()
            LIST_TOP -> store.topTracks()
            else -> list.toLongOrNull()?.let { id -> store.playlistItemDetails(id).map { it.mediaItem } }.orEmpty()
        }
    }

    private fun MediaStoreItem.toMediaItem(containerId: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(listOfNotNull(artist, album).joinToString(" · ").ifEmpty { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
        localArtUrl(this)?.let { metadata.setArtworkUri(artworkContentUri(context, it)) }
        return MediaItem.Builder()
            .setMediaId("$LOCAL_SONG_PREFIX$containerId/$id")
            .setMediaMetadata(metadata.build())
            .build()
    }

    /** "song:<containerId>/<rowId>" → containerId + rowId; the container is the play context. */
    fun parseLocalSongId(mediaId: String): Pair<String, Long>? {
        val body = mediaId.removePrefix(LOCAL_SONG_PREFIX)
        val rowId = body.substringAfterLast('/').toLongOrNull() ?: return null
        return body.substringBeforeLast('/') to rowId
    }

    // ---- engine feeds ----

    /** Shelves flattened into one list: videos playable, playlists/albums browsable, each tagged
     *  with its shelf title as a group heading; channels and nav tags have no Auto surface. */
    private fun feedChildren(feed: YtFeedPage): List<MediaItem> = feed.sections.flatMap { section ->
        val group = section.header?.title
        section.items.mapNotNull { item ->
            when (item.kind) {
                YtFeedItem.KIND_VIDEO -> {
                    remember(item)
                    item.toMediaItem(group)
                }
                YtFeedItem.KIND_PLAYLIST -> item.url?.let { url ->
                    browsable(
                        YT_LIST_PREFIX + url,
                        item.title,
                        subtitle = item.subtitleLine1,
                        artUrl = item.thumbs.lastOrNull()?.url,
                        group = group,
                        style = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    )
                }
                else -> null
            }
        }
    }

    @Synchronized
    private fun remember(item: YtFeedItem) {
        ytItems[item.id] = item
    }

    private fun YtFeedItem.toMediaItem(groupTitle: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitleLine1)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(styleExtras(group = groupTitle, style = null))
        // The stable mqdefault url derived from the video id, NOT a feed thumb url — those carry
        // rotating signature params and expire inside long-lived browse/session uris.
        metadata.setArtworkUri(artworkContentUri(context, youtubeThumbUrl(id)))
        return MediaItem.Builder()
            .setMediaId(YT_SONG_PREFIX + id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    // ---- item builders ----

    private fun browsable(
        id: String,
        title: String,
        subtitle: String? = null,
        artUrl: String? = null,
        group: String? = null,
        style: Int? = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setExtras(styleExtras(group, style))
        artUrl?.let { metadata.setArtworkUri(artworkContentUri(context, it)) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun styleExtras(group: String?, style: Int?): Bundle = Bundle().apply {
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        style?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
    }

    companion object {
        const val ROOT = "root"
        const val TAB_LIBRARY = "tab_library"
        const val TAB_DISCOVER = "tab_discover"
        const val TAB_YTMUSIC = "tab_ytmusic"
        const val LIB_SONGS = "lib_songs"
        const val LIB_PLAYLISTS = "lib_playlists"
        const val DISC_TRENDING = "disc_trending"
        const val DISC_HOME = "disc_home"
        const val YTM_HOME = "ytm_home"
        const val YTM_EXPLORE = "ytm_explore"
        const val YTM_NEW = "ytm_new"
        const val YTM_CHARTS = "ytm_charts"

        /** Playable YouTube video: `yt:<videoId>`. */
        const val YT_SONG_PREFIX = "yt:"

        /** Browsable engine playlist/album: `ytlist:<engine app link, verbatim>`. */
        const val YT_LIST_PREFIX = "ytlist:"

        /** Playable local song: `song:<containerId>/<rowId>` — the container is the play context. */
        const val LOCAL_SONG_PREFIX = "song:"

        /** Browsable local list: `list:<playlistId | recent | top>`. */
        const val LOCAL_LIST_PREFIX = "list:"

        const val LIST_RECENT = "recent"
        const val LIST_TOP = "top"

        private const val YT_CACHE_MAX = 1000
    }
}
