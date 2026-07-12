package ymusic.dynamicengine.yt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.Buffer
import ymusic.dynamicengine.DynamicEngine
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.EngineError
import ymusic.dynamicengine.decodeEngineError
import ymusic.quickjs.PipeResetException

/**
 * The YouTube metadata task methods — typed Kotlin twins of the engine bundle's `yt/…` commands
 * (engine-js `logic/yt/commands.ts`). Each call pins the manager's current engine, sends one JSON
 * args head, reads one JSON result to EOF, and decodes an engine RESET into the typed
 * [EngineError]. Which browseIds/params the app queries is product policy above this layer; these
 * are the transport-typed verbs.
 */

/** Any browse surface — home (`FEwhat_to_watch`), playlists (`VL…`), channels (`UC…`), … */
suspend fun DynamicEngineManager.browse(
    browseId: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/browse", BrowseArgs(browseId, params, continuation, ctx))

suspend fun DynamicEngineManager.search(
    query: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/search", SearchArgs(query, params, continuation, ctx))

/** Watch-next: related items + [YtFeedPage.autoplayVideoId] for [videoId] (or a continuation). */
suspend fun DynamicEngineManager.watchNext(
    videoId: String? = null,
    playlistId: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/next", NextArgs(videoId, playlistId, params, continuation, ctx))

suspend fun DynamicEngineManager.searchSuggestions(query: String, ctx: YtContext = YtContext()): List<String> =
    command<SuggestArgs, Suggestions>("yt/suggest", SuggestArgs(query, ctx)).suggestions

@Serializable
private data class BrowseArgs(
    val browseId: String?,
    val params: String?,
    val continuation: String?,
    val ctx: YtContext,
)

@Serializable
private data class SearchArgs(
    val query: String?,
    val params: String?,
    val continuation: String?,
    val ctx: YtContext,
)

@Serializable
private data class NextArgs(
    val videoId: String?,
    val playlistId: String?,
    val params: String?,
    val continuation: String?,
    val ctx: YtContext,
)

@Serializable
private data class SuggestArgs(val query: String, val ctx: YtContext)

@Serializable
private data class Suggestions(val suggestions: List<String>)

/** Bundles may add wire fields before the app updates — never a decode failure. */
private val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private suspend inline fun <reified A : Any> DynamicEngineManager.feedCommand(command: String, args: A): YtFeedPage =
    command<A, YtFeedPage>(command, args)

private suspend inline fun <reified A : Any, reified R> DynamicEngineManager.command(command: String, args: A): R {
    val body = use { it.jsonCommand(command, wireJson.encodeToString(serializer<A>(), args)) }
    return try {
        wireJson.decodeFromString<R>(body)
    } catch (e: Exception) {
        throw EngineError.Technical(
            "E20030",
            "$command returned a malformed result: ${body.take(200)}",
            null,
            null,
            null,
            e,
        )
    }
}

/** One args-head → one JSON-body round trip; a RESET decodes to the typed [EngineError]. */
internal suspend fun DynamicEngine.jsonCommand(command: String, argsJson: String): String {
    val pipe = openPipe(command)
    try {
        val head = Buffer().writeUtf8(argsJson)
        pipe.sink.send(head, head.size)
        pipe.sink.close()
        val out = Buffer()
        try {
            while (pipe.source.receive(out, 8192L) != -1L) { /* to EOF */ }
        } catch (e: PipeResetException) {
            throw decodeEngineError(e.reason, e)
        }
        return out.readUtf8()
    } finally {
        pipe.sink.close()
        pipe.source.close()
    }
}
