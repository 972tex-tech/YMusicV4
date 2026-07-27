package ymusic.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtThumb
import ymusic.dynamicengine.yt.watchNext
import ymusic.mediastore.model.MediaStoreItem
import ymusic.player.PlayableItem
import ymusic.player.Player

/**
 * UI-side playback glue over the [Player] facade: remembers the display metadata of every queued
 * item (the queue itself holds only [PlayableItem]s), and implements "play now" as YouTube-style
 * autoplay — load the tapped video, then append its watch-next as the rest of the queue.
 */
class PlaybackCoordinator(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val _metadata = MutableStateFlow<Map<String, YtFeedItem>>(emptyMap())

    /** Display metadata by [PlayableItem] key (videoId / local uri). */
    val metadata: StateFlow<Map<String, YtFeedItem>> = _metadata.asStateFlow()

    val player: Player get() = graph.player

    fun metadataFor(item: PlayableItem?): YtFeedItem? = when (item) {
        is PlayableItem.YtVideoId -> _metadata.value[item.videoId]
        is PlayableItem.LocalUri -> _metadata.value[item.uri]
        null -> null
    }

    fun titleFor(item: PlayableItem?): String = metadataFor(item)?.title ?: when (item) {
        is PlayableItem.YtVideoId -> item.videoId
        is PlayableItem.LocalUri -> item.uri.substringAfterLast('/')
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
        _metadata.update { it + songs.map(::localDisplay).associateBy(YtFeedItem::id) }
        player.setQueue(songs.map { PlayableItem.LocalUri(it.uri.toString()) }, playIndex = index)
    }

    /** Play just [song] now (queue of one). */
    fun playLocalSingle(song: MediaStoreItem) {
        player.setQueue(listOf(rememberLocal(song)))
    }

    /** Queue [song] right after the current item. */
    fun queueLocalNext(song: MediaStoreItem) {
        player.addToPlayNext(listOf(rememberLocal(song)))
    }

    /** Append [song] to the end of the queue. */
    fun queueLocalLast(song: MediaStoreItem) {
        player.add(listOf(rememberLocal(song)))
    }

    private fun localDisplay(s: MediaStoreItem) = YtFeedItem(
        kind = KIND_LOCAL,
        id = s.uri.toString(),
        title = s.title,
        subtitleLine1 = listOfNotNull(s.artist, s.album).joinToString(" · ").ifEmpty { null },
        thumbs = localArtUrl(s)?.let { listOf(YtThumb(it)) } ?: emptyList(),
    )

    private fun rememberLocal(song: MediaStoreItem): PlayableItem {
        _metadata.update { it + (song.uri.toString() to localDisplay(song)) }
        return PlayableItem.LocalUri(song.uri.toString())
    }

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
