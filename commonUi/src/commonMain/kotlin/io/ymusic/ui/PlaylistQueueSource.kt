package io.ymusic.ui

import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtFeedPage
import io.ymusic.dynamicengine.yt.extractMusicBrowse
import io.ymusic.dynamicengine.yt.extractPlaylistDetail
import io.ymusic.player.PlayableItem
import io.ymusic.player.QueueSource
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import projectcommon.json.intOrNull
import projectcommon.json.string

/**
 * A playlist's pages past the ones a detail screen already loaded, feeding the queue that screen
 * started. Videos already queued are skipped; the list ends when the server sends no continuation.
 * [url] is the detail page's engine link, round-tripped verbatim to the [kind]'s command.
 */
internal class PlaylistQueueSource(
    private val engines: DynamicEngineManager,
    private val kind: Kind,
    private val url: String,
    private var continuation: String?,
    override val totalCount: Int?,
    queuedVideoIds: Collection<String>,
    private val log: Logger?,
) : QueueSource {
    enum class Kind(val token: String) { Playlist("playlist"), MusicBrowse("music") }

    private val seen = HashSet(queuedVideoIds)

    override val resumeToken: String?
        get() = continuation?.let { next ->
            buildJsonObject {
                put("kind", kind.token)
                put("url", url)
                put("continuation", next)
                put("total", totalCount)
            }.toString()
        }

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

    private suspend fun fetch(token: String): YtFeedPage = when (kind) {
        Kind.Playlist -> engines.extractPlaylistDetail(url, continuation = token, logger = log)
        Kind.MusicBrowse -> engines.extractMusicBrowse(url, continuation = token, logger = log)
    }

    companion object {
        /** The source a saved [QueueSource.resumeToken] describes, continuing past [queued]; null for a token
         *  this build can't read. */
        fun resume(token: String, engines: DynamicEngineManager, queued: List<PlayableItem>, log: Logger?): PlaylistQueueSource? {
            val json = try {
                Json.parseToJsonElement(token) as JsonObject
            } catch (e: Exception) {
                log?.log(Logger.Level.WARN, "unreadable queue resume token", e)
                return null
            }
            val kind = Kind.entries.firstOrNull { it.token == json.string("kind") } ?: return null
            return PlaylistQueueSource(
                engines,
                kind,
                url = json.string("url"),
                continuation = json.string("continuation"),
                totalCount = json.intOrNull("total"),
                queuedVideoIds = queued.mapNotNull { (it as? PlayableItem.YtMedia)?.videoId },
                log = log,
            )
        }
    }
}

/** The queue source continuing this page's list past [loaded]; null when the page holds it all. */
internal fun Pager.State.Ready?.queueSource(
    engines: DynamicEngineManager,
    kind: PlaylistQueueSource.Kind,
    url: String,
    loaded: List<YtFeedItem>,
    log: Logger?,
): QueueSource? {
    val ready = this ?: return null
    val continuation = ready.continuation ?: return null
    return PlaylistQueueSource(
        engines,
        kind,
        url,
        continuation,
        ready.pageHeader?.itemCount,
        loaded.map { it.id },
        log?.fork("queue-source"),
    )
}
