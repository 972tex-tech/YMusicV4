package ymusic.dynamicengine.yt

import kmphttp.json.JsonReader
import kmphttp.json.JsonWriter
import kotlinx.coroutines.CancellationException
import ymusic.dynamicengine.DynamicEngine
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.EngineError
import ymusic.dynamicengine.decodeEngineError
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.asAsyncSink
import ymusic.quickjs.asBufferedSource

/**
 * The YouTube metadata task methods — typed Kotlin twins of the engine bundle's `yt/…` commands
 * (engine-js `logic/yt/commands.ts`). Each call pins the manager's current engine, streams one JSON
 * args head to the pipe with a [JsonWriter], stream-parses one JSON result back with a [JsonReader],
 * and decodes an engine RESET into the typed [EngineError] — no reflective (de)serializer touches the
 * wire. Which browseIds/params the app queries is product policy above this layer; these are the
 * transport-typed verbs.
 */

/** Any browse surface — home (`FEwhat_to_watch`), playlists (`VL…`), channels (`UC…`), … */
suspend fun DynamicEngineManager.browse(
    browseId: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/browse") { w ->
    w.beginObject()
    w.optionalField("browseId", browseId)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
    w.contextField(ctx)
    w.endObject()
}

suspend fun DynamicEngineManager.search(
    query: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/search") { w ->
    w.beginObject()
    w.optionalField("query", query)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
    w.contextField(ctx)
    w.endObject()
}

/** Watch-next: related items + [YtFeedPage.autoplayVideoId] for [videoId] (or a continuation). */
suspend fun DynamicEngineManager.watchNext(
    videoId: String? = null,
    playlistId: String? = null,
    params: String? = null,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/next") { w ->
    w.beginObject()
    w.optionalField("videoId", videoId)
    w.optionalField("playlistId", playlistId)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
    w.contextField(ctx)
    w.endObject()
}

suspend fun DynamicEngineManager.searchSuggestions(query: String, ctx: YtContext = YtContext()): List<String> =
    use { engine ->
        engine.jsonCommand("yt/suggest", { w ->
            w.beginObject()
            w.name("query").value(query)
            w.contextField(ctx)
            w.endObject()
        }) { readSuggestions(it) }
    }

/** Write `"key":value` only when [value] is present — absent optional fields are omitted, not null. */
private suspend fun JsonWriter.optionalField(key: String, value: String?) {
    if (value != null) name(key).value(value)
}

/** The `ctx` object carried on every InnerTube call (localization + visitor identity). */
private suspend fun JsonWriter.contextField(ctx: YtContext) {
    name("ctx").beginObject()
    name("hl").value(ctx.hl)
    name("gl").value(ctx.gl)
    ctx.visitorData?.let { name("visitorData").value(it) }
    endObject()
}

private suspend fun DynamicEngineManager.feedCommand(
    command: String,
    writeArgs: suspend (JsonWriter) -> Unit,
): YtFeedPage = use { engine -> engine.jsonCommand(command, writeArgs) { readFeedPage(it) } }

/**
 * Stream the args head to the pipe sink with [writeArgs], then stream-parse the result off the pipe
 * source with [read] — neither side is buffered whole. A RESET decodes to the typed [EngineError]; a
 * malformed body surfaces as `E20030`.
 */
internal suspend fun <R> DynamicEngine.jsonCommand(
    command: String,
    writeArgs: suspend (JsonWriter) -> Unit,
    read: suspend (JsonReader) -> R,
): R {
    val pipe = openPipe(command)
    try {
        val writer = JsonWriter(pipe.sink.asAsyncSink())
        writeArgs(writer)
        writer.flush()
        pipe.sink.close()
        return try {
            read(JsonReader(pipe.source.asBufferedSource()))
        } catch (e: PipeResetException) {
            throw decodeEngineError(e.reason, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineError.Technical(
                "E20030",
                "$command returned a malformed result: ${e.message}",
                null,
                null,
                null,
                e,
            )
        }
    } finally {
        pipe.sink.close()
        pipe.source.close()
    }
}
