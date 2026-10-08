package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.player.QueueSource
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException

/**
 * A playlist's pages past the ones a detail screen already loaded, feeding the queue that screen
 * started. Videos already queued are skipped; the list ends when the server sends no continuation.
 */
internal class PlaylistQueueSource(
    private val fetch: suspend (continuation: String) -> YtFeedPage,
    private var continuation: String?,
    override val totalCount: Int?,
    loaded: List<YtFeedItem>,
    private val log: Logger?,
) : QueueSource {
    private val seen = loaded.mapTo(HashSet()) { it.id }

    override suspend fun loadMore(): QueueSource.Page {
        val token = continuation ?: return QueueSource.Page(emptyList(), hasMore = false)
        val page = try {
            fetch(token)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log?.log(Logger.Level.WARN, "queue page failed", e)
            throw e
        }
        continuation = page.continuation
        val items = page.sections.flatMap { it.items }
            .filter { it.kind == YtFeedItem.KIND_VIDEO && seen.add(it.id) }
            .map { it.toYtMedia() }
        return QueueSource.Page(items, hasMore = continuation != null)
    }
}

/** The queue source continuing this page's list past [loaded]; null when the page holds it all. */
internal fun Pager.State.Ready?.queueSource(
    loaded: List<YtFeedItem>,
    log: Logger?,
    fetch: suspend (continuation: String?) -> YtFeedPage,
): QueueSource? {
    val ready = this ?: return null
    val continuation = ready.continuation ?: return null
    return PlaylistQueueSource({ fetch(it) }, continuation, ready.pageHeader?.itemCount, loaded, log?.fork("queue-source"))
}
