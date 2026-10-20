package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.extractMusicBrowse
import io.ymusic.dynamicengine.yt.extractPlaylistDetail
import io.ymusic.dynamicengine.yt.musicSearch

/** The kind of thing a voice request names (Android's EXTRA_MEDIA_FOCUS); [Any] = unspecified. */
enum class SearchFocus { Any, Song, Artist, Album, Playlist }

/**
 * Play what a voice command asks for ("play Eminem on YMusic"): the music search's top result, or
 * with a [focus] the first result of that kind. A song plays with its radio, an artist its top song
 * with radio, an album / playlist through its list. False when nothing playable matched.
 */
suspend fun PlaybackCoordinator.playFromSearch(query: String, focus: SearchFocus = SearchFocus.Any): Boolean {
    val engines = graph.engines
    val log = graph.logger?.fork("play-from-search")
    val results = engines.musicSearch(query = query, logger = log).items()
    val wantedKind = when (focus) {
        SearchFocus.Song -> YtFeedItem.KIND_VIDEO
        SearchFocus.Artist -> YtFeedItem.KIND_CHANNEL
        SearchFocus.Album, SearchFocus.Playlist -> YtFeedItem.KIND_PLAYLIST
        SearchFocus.Any -> null
    }
    val pick = wantedKind?.let { kind -> results.firstOrNull { it.kind == kind } }
        ?: results.firstOrNull { it.kind in PLAYABLE_KINDS }
        ?: return false
    val url = pick.url
    if (pick.kind == YtFeedItem.KIND_VIDEO || url == null) {
        val video = pick.takeIf { it.kind == YtFeedItem.KIND_VIDEO }
            ?: results.firstOrNull { it.kind == YtFeedItem.KIND_VIDEO }
            ?: return false
        playNow(video)
        return true
    }
    val page = if (url.startsWith(MusicBrowsePrefix)) {
        engines.extractMusicBrowse(url, logger = log)
    } else {
        engines.extractPlaylistDetail(url, logger = log)
    }
    val songs = page.items().filter { it.kind == YtFeedItem.KIND_VIDEO }
    if (songs.isEmpty()) return false
    if (pick.kind == YtFeedItem.KIND_CHANNEL) {
        playNow(songs.first())
    } else {
        playAll(songs, source = NextQueueSource.playlist(engines, url, songs, page.pageHeader?.itemCount, log?.fork("queue-source")))
    }
    return true
}

private val PLAYABLE_KINDS = setOf(YtFeedItem.KIND_VIDEO, YtFeedItem.KIND_CHANNEL, YtFeedItem.KIND_PLAYLIST)

private fun YtFeedPage.items(): List<YtFeedItem> = sections.flatMap { it.items }
