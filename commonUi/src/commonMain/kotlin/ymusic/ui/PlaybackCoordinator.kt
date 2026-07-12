package ymusic.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.watchNext
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
                val next = graph.engines.watchNext(videoId = video.id, ctx = graph.ytContext)
                val related = next.items.filter { it.kind == YtFeedItem.KIND_VIDEO && it.id != video.id }
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
}
