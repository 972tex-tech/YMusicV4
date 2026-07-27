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
 * The YouTube extraction surface — intent-level twins of the engine bundle's `yt/…` commands
 * (engine-js `logic/yt/commands.ts`), shaped like the legacy `ExtractorLibWrapper`: the app states
 * WHAT it wants (the home feed, a playlist page behind an app link, a search), never HOW YouTube
 * serves it. All browseIds, params protobufs and Mix mechanics live in the engine; the app's only
 * currencies are engine-composed app links (round-tripped verbatim), opaque continuations, and the
 * user's own input (a query, a tapped video). Each call pins the manager's current engine, streams
 * one JSON args head to the pipe with a [JsonWriter], stream-parses one JSON result back with a
 * [JsonReader], and decodes an engine RESET into the typed [EngineError] — no reflective
 * (de)serializer touches the wire.
 */

/** The Discover home feed (first page or a page's [YtFeedPage.continuation]). */
suspend fun DynamicEngineManager.extractHomeFeed(
    continuation: String? = null,
): YtFeedPage = feedCommand("yt/home") { w ->
    w.beginObject()
    w.optionalField("continuation", continuation)
    w.endObject()
}

/**
 * Hand the engine the watch-next payload the WebView player's page reported for the music video it
 * is playing ([contents] is that payload's JSON text, forwarded VERBATIM — the app never parses it).
 * The engine parses and stores it; it is the only thing feeding [getRecommendedFeed]. Fire and
 * forget: nothing comes back, and a failure here must never disturb playback.
 */
suspend fun DynamicEngineManager.handleWebViewPlayNext(videoId: String, contents: String) {
    use { engine ->
        engine.jsonCommand("yt/webview-next", { w ->
            w.beginObject()
            w.name("videoId").value(videoId)
            w.name("contents").value(contents)
            w.endObject()
        }) { it.skipValue() }
    }
}

/**
 * The Recommended feed — built entirely from what [handleWebViewPlayNext] harvested, so it needs no
 * network and returns an empty page until something has been played. Re-shuffled on every call.
 */
suspend fun DynamicEngineManager.getRecommendedFeed(): YtFeedPage = feedCommand("yt/recommended") { w ->
    w.beginObject()
    w.endObject()
}

/**
 * The Trending Music feed — the charts YouTube publishes for the engine-resolved region (the user's
 * pick, else the connection's detected region): trending music videos, top songs, top music videos,
 * top songs on Shorts. One page: charts publish no continuation. Section titles are
 * engine-synthesized and carry a [YtSectionHeader.titleKey] the app localizes.
 */
suspend fun DynamicEngineManager.extractTrendingMusicFeed(): YtFeedPage = feedCommand("yt/trending") { w ->
    w.beginObject()
    w.endObject()
}

/**
 * A playlist detail page. [url] is an engine-composed app link ("/playlist?list=…", possibly with
 * engine-private query state) from a [YtFeedItem.url] or [YtSectionHeader.ctaDeeplink] — passed back
 * VERBATIM; the engine decides how to serve it (VL browse, Mix watch-panel, …).
 */
suspend fun DynamicEngineManager.extractPlaylistDetail(
    url: String,
    continuation: String? = null,
): YtFeedPage = feedCommand("yt/playlist") { w ->
    w.beginObject()
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
    w.endObject()
}

/** A channel detail page. [url] is an engine-composed "/channel/…" app link (an item's, a tab's, or
 *  a shelf CTA's), passed back verbatim — same contract as [extractPlaylistDetail]. */
suspend fun DynamicEngineManager.extractChannelDetail(
    url: String,
    continuation: String? = null,
): YtFeedPage = feedCommand("yt/channel") { w ->
    w.beginObject()
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
    w.endObject()
}

suspend fun DynamicEngineManager.search(
    query: String? = null,
    params: String? = null,
    continuation: String? = null,
): YtFeedPage = feedCommand("yt/search") { w ->
    w.beginObject()
    w.optionalField("query", query)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
    w.endObject()
}

/** Watch-next: related items + [YtFeedPage.autoplayVideoId] for [videoId] (or a continuation). */
suspend fun DynamicEngineManager.watchNext(
    videoId: String? = null,
    continuation: String? = null,
): YtFeedPage = feedCommand("yt/next") { w ->
    w.beginObject()
    w.optionalField("videoId", videoId)
    w.optionalField("continuation", continuation)
    w.endObject()
}

suspend fun DynamicEngineManager.searchSuggestions(query: String): List<String> =
    use { engine ->
        engine.jsonCommand("yt/suggest", { w ->
            w.beginObject()
            w.name("query").value(query)
            w.endObject()
        }) { readSuggestions(it) }
    }

/**
 * The locale-changed notification (the legacy `YTPrefsUpdateTask` role). [gl] is the region pick,
 * blank = automatic (the slot leaves PREF, so the bootstrap homepage answers with YouTube's
 * IP-derived region again) — PREF itself, the persistent WebView-shared cookie store, is the
 * pick's one record. There is no hl argument — the app language IS the content language, and the
 * engine re-pulls it from host settings on every notify (only the hl slot is re-asserted on every
 * cooked request; the gl slot is YouTube's to control). On a real change the engine deletes the
 * sticky `s_gl` echo and refreshes its session under the new identity: the re-parsed ytcfg GL
 * becomes the working region value — YouTube outranks the pick. Call when the user picks a region
 * OR a language (after persisting the language setting) — never on start.
 */
suspend fun DynamicEngineManager.applyYtLocale(gl: String): Unit = use { engine ->
    engine.jsonCommand("yt/locale/apply", { w ->
        w.beginObject()
        w.name("gl").value(gl)
        w.endObject()
    }) { it.skipValue() }
}

/**
 * The Settings region picker's one read: the WORKING region ([YtLocales.gl] — the session's parsed
 * answer, YouTube's own) and the menus YouTube offers labelled in the effective language. The
 * engine keeps ONE cached copy of the menus (in-memory + [ymusic.dynamicengine.CacheStore]) tagged
 * with the pair it was fetched under: it re-fetches only when the effective pair changed or the
 * copy is over a week old, and a failed re-fetch falls back to the cached copy instead of erroring.
 */
suspend fun DynamicEngineManager.ytLocaleOptions(): YtLocales = use { engine ->
    engine.jsonCommand("yt/locales", { w ->
        w.beginObject()
        w.endObject()
    }) { readLocales(it) }
}

/**
 * The YouTube `/embed` page for the WebView player, fetched and rewritten by the engine: the fetch
 * rides the engine's cooked cookie identity (one identity with all scraping), and the HTML splice
 * (CSP stripped, script preloads defanged, the boot-script slot inserted first in `<head>`) is
 * remotely updatable. The one header exception: [userAgent] must be the WebView's OWN UA, carried
 * verbatim — the page has to look loaded by the WebView that runs it (playback-identity rule).
 * The returned HTML holds the `INJECT_JS_HERE` placeholder where the caller splices its boot
 * script (session globals + the `webview_ymusic.js` bundle, which stays app-side).
 */
suspend fun DynamicEngineManager.embedPage(userAgent: String): String = use { engine ->
    engine.jsonCommand("yt/embed", { w ->
        w.beginObject()
        w.name("userAgent").value(userAgent)
        w.endObject()
    }) { readEmbedHtml(it) }
}

/** Write `"key":value` only when [value] is present — absent optional fields are omitted, not null. */
private suspend fun JsonWriter.optionalField(key: String, value: String?) {
    if (value != null) name(key).value(value)
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
