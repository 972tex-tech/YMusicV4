package ymusic.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtThumb
import ymusic.dynamicengine.yt.watchNext
import ymusic.mediastore.model.MediaStoreItem
import ymusic.player.PlayableItem
import ymusic.player.Player

/**
 * UI-side playback glue over the [Player] facade. Every queue item is self-describing — a
 * [PlayableItem.LocalMedia] / [PlayableItem.YtMedia] carries its own id + display metadata — so the
 * coordinator holds NO side map: Now Playing reads the item's `display*` accessors straight off the
 * item, and play-recording keys on the item's own id. Implements "play now" as YouTube-style autoplay — load the
 * tapped video, then append its watch-next as the rest of the queue.
 */
class PlaybackCoordinator(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
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
                        val id = (item as? PlayableItem.LocalMedia)?.mediaId ?: return@collect
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

    /** The video to hand the download launcher — only a YouTube item is downloadable (local → null).
     *  The one place a queue item is rebuilt into a [YtFeedItem], for the launcher's API. */
    fun downloadable(item: PlayableItem?): YtFeedItem? = (item as? PlayableItem.YtMedia)?.let {
        YtFeedItem(
            kind = YtFeedItem.KIND_VIDEO,
            id = it.videoId,
            title = it.title ?: it.videoId,
            subtitleLine1 = it.artist,
            duration = it.duration,
            thumbs = it.thumbnailUrl?.let { url -> listOf(YtThumb(url)) } ?: emptyList(),
        )
    }

    /** Play [video] now; its watch-next related videos become the rest of the queue (autoplay). */
    fun playNow(video: YtFeedItem) {
        player.setQueue(listOf(video.toYtMedia()))
        scope.launch {
            try {
                val next = graph.engines.watchNext(videoId = video.id)
                val related = next.sections.flatMap { it.items }
                    .filter { it.kind == YtFeedItem.KIND_VIDEO && it.id != video.id }
                // The queue may have changed while we fetched — only extend our own load.
                val current = player.queue.value
                if ((current.currentItem as? PlayableItem.YtMedia)?.videoId == video.id && current.items.size == 1) {
                    player.add(related.map { it.toYtMedia() })
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
        player.setShuffle(shuffle)
        player.setQueue(
            playable.map { it.toYtMedia() },
            playIndex = startIndex.coerceIn(0, playable.size - 1),
        )
    }

    /** Play local library [songs] as the queue, starting at [index]. */
    fun playLocal(songs: List<MediaStoreItem>, index: Int) {
        player.setQueue(songs.map { it.toLocalMedia() }, playIndex = index)
    }

    /** Play just [song] now (queue of one). */
    fun playLocalSingle(song: MediaStoreItem) {
        player.setQueue(listOf(song.toLocalMedia()))
    }

    /** Play a local file by uri now (queue of one) with the given display metadata — for a downloaded
     *  item, which may not be a [MediaStoreItem] (no download location set, so no [PlayableItem.LocalMedia.mediaId]). */
    fun playLocalUri(uri: String, title: String, artist: String?, thumbnailUrl: String?) {
        player.setQueue(listOf(PlayableItem.LocalMedia(uri = uri, title = title, artist = artist, thumbnailUrl = thumbnailUrl)))
    }

    /** Queue [song] right after the current item. */
    fun queueLocalNext(song: MediaStoreItem) {
        player.addToPlayNext(listOf(song.toLocalMedia()))
    }

    /** Append [song] to the end of the queue. */
    fun queueLocalLast(song: MediaStoreItem) {
        player.add(listOf(song.toLocalMedia()))
    }

    fun playNext(video: YtFeedItem) {
        player.addToPlayNext(listOf(video.toYtMedia()))
    }

    fun playLater(video: YtFeedItem) {
        player.add(listOf(video.toYtMedia()))
    }

    // ---- self-describing queue items ----

    /** A library item as a self-describing queue item (id + display metadata travel with it). */
    private fun MediaStoreItem.toLocalMedia() = PlayableItem.LocalMedia(
        uri = uri.toString(),
        mediaId = id,
        title = title,
        artist = artist,
        album = album,
        thumbnailUrl = localArtUrl(this),
    )

    /** A scraped video as a self-describing queue item — keeps just what the UI + downloads need. */
    private fun YtFeedItem.toYtMedia() = PlayableItem.YtMedia(
        videoId = id,
        title = title,
        artist = subtitleLine1,
        thumbnailUrl = thumbs.firstOrNull()?.url,
        duration = duration,
    )
}
