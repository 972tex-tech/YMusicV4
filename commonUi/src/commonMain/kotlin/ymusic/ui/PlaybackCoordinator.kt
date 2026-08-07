package ymusic.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtThumb
import ymusic.dynamicengine.yt.watchNext
import ymusic.mediastore.model.MediaStoreItem
import ymusic.player.PlayableItem
import ymusic.player.Player

/**
 * UI-side playback glue over the [Player] facade. Local items are self-describing — a
 * [PlayableItem.LocalUri] carries its own id + display metadata — so only YouTube items need the
 * [metadata] side map (their metadata is fetched separately, e.g. watch-next). Implements "play now"
 * as YouTube-style autoplay — load the tapped video, then append its watch-next as the rest of the queue.
 */
class PlaybackCoordinator(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val _metadata = MutableStateFlow<Map<String, YtFeedItem>>(emptyMap())

    /** Display metadata for YouTube items, by video id (local items carry their own — see [LocalUri]). */
    val metadata: StateFlow<Map<String, YtFeedItem>> = _metadata.asStateFlow()

    val player: Player get() = graph.player

    init {
        // Record library plays for the computed "Recent play" / "Top tracks" playlists: every time a
        // local library item becomes the current item, bump its play stats by row id (carried on the
        // item, stable across moves/rescans). Items with no id (a stream, or a download with no
        // library row) simply aren't recorded.
        val store = graph.mediaStore
        if (store != null) {
            scope.launch {
                player.queue
                    .map { it.currentItem }
                    .distinctUntilChanged()
                    .collect { item ->
                        val id = (item as? PlayableItem.LocalUri)?.mediaId ?: return@collect
                        try {
                            store.recordLocalPlay(id)
                        } catch (e: Exception) {
                            // Play stats are best-effort; a failure never interrupts playback.
                            println("recordLocalPlay failed: $e")
                        }
                    }
            }
        }
    }

    fun metadataFor(item: PlayableItem?): YtFeedItem? = when (item) {
        is PlayableItem.YtVideoId -> _metadata.value[item.videoId]
        is PlayableItem.LocalUri -> item.toDisplay()
        null -> null
    }

    fun titleFor(item: PlayableItem?): String = when (item) {
        is PlayableItem.YtVideoId -> _metadata.value[item.videoId]?.title ?: item.videoId
        is PlayableItem.LocalUri -> item.title ?: item.uri.substringAfterLast('/')
        null -> ""
    }

    /** Play [video] now; its watch-next related videos become the rest of the queue (autoplay). */
    fun playNow(video: YtFeedItem) {
        remember(listOf(video))
        player.setQueue(listOf(PlayableItem.YtVideoId(video.id)))
        scope.launch {
            try {
                val next = graph.engines.watchNext(videoId = video.id)
                val related = next.sections.flatMap { it.items }
                    .filter { it.kind == YtFeedItem.KIND_VIDEO && it.id != video.id }
                remember(related)
                // The queue may have changed while we fetched — only extend our own load.
                val current = player.queue.value
                if ((current.currentItem as? PlayableItem.YtVideoId)?.videoId == video.id && current.items.size == 1) {
                    player.add(related.map { PlayableItem.YtVideoId(it.id) })
                }
            } catch (e: Exception) {
                // Autoplay fill is an enhancement, not the task — the tapped video still plays.
                println("watch-next fill failed: $e")
            }
        }
    }

    /** Play [videos] as the whole queue (a playlist page), starting at [startIndex]. */
    fun playAll(videos: List<YtFeedItem>, startIndex: Int = 0, shuffle: Boolean = false) {
        val playable = videos.filter { it.kind == YtFeedItem.KIND_VIDEO }
        if (playable.isEmpty()) return
        remember(playable)
        player.setShuffle(shuffle)
        player.setQueue(
            playable.map { PlayableItem.YtVideoId(it.id) },
            playIndex = startIndex.coerceIn(0, playable.size - 1),
        )
    }

    /** Play local library [songs] as the queue, starting at [index]. */
    fun playLocal(songs: List<MediaStoreItem>, index: Int) {
        player.setQueue(songs.map { it.toLocalUri() }, playIndex = index)
    }

    /** Play just [song] now (queue of one). */
    fun playLocalSingle(song: MediaStoreItem) {
        player.setQueue(listOf(song.toLocalUri()))
    }

    /** Play a local file by uri now (queue of one) with the given display metadata — for a downloaded
     *  item, which may not be a [MediaStoreItem] (no download location set, so no [LocalUri.mediaId]). */
    fun playLocalUri(uri: String, title: String, artist: String?, thumbnailUrl: String?) {
        player.setQueue(listOf(PlayableItem.LocalUri(uri = uri, title = title, artist = artist, thumbnailUrl = thumbnailUrl)))
    }

    /** Queue [song] right after the current item. */
    fun queueLocalNext(song: MediaStoreItem) {
        player.addToPlayNext(listOf(song.toLocalUri()))
    }

    /** Append [song] to the end of the queue. */
    fun queueLocalLast(song: MediaStoreItem) {
        player.add(listOf(song.toLocalUri()))
    }

    /** A library item as a self-describing queue item (id + display metadata travel with it). */
    private fun MediaStoreItem.toLocalUri() = PlayableItem.LocalUri(
        uri = uri.toString(),
        mediaId = id,
        title = title,
        artist = artist,
        album = album,
        thumbnailUrl = localArtUrl(this),
    )

    /** Build the Now Playing display model from a local item's own fields (no side map). */
    private fun PlayableItem.LocalUri.toDisplay() = YtFeedItem(
        kind = KIND_LOCAL,
        id = uri,
        title = title ?: uri.substringAfterLast('/'),
        subtitleLine1 = listOfNotNull(artist, album).joinToString(" · ").ifEmpty { null },
        thumbs = thumbnailUrl?.let { listOf(YtThumb(it)) } ?: emptyList(),
    )

    fun playNext(video: YtFeedItem) {
        remember(listOf(video))
        player.addToPlayNext(listOf(PlayableItem.YtVideoId(video.id)))
    }

    fun playLater(video: YtFeedItem) {
        remember(listOf(video))
        player.add(listOf(PlayableItem.YtVideoId(video.id)))
    }

    private fun remember(items: List<YtFeedItem>) {
        if (items.isEmpty()) return
        _metadata.update { it + items.associateBy(YtFeedItem::id) }
    }

    private companion object {
        /** Display-metadata kind for local library items (keyed by uri, never a YouTube id). */
        const val KIND_LOCAL = "local"
    }
}
