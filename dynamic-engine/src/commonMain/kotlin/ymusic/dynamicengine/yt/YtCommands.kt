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
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/home") { w ->
    w.beginObject()
    w.optionalField("continuation", continuation)
    w.contextField(ctx)
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
 * The Trending Music feed for [ctx]'s region — the charts YouTube publishes there (trending music
 * videos, top songs, top music videos, top songs on Shorts). One page: charts publish no
 * continuation. Section titles are engine-synthesized and carry a [YtSectionHeader.titleKey] the app
 * localizes.
 */
suspend fun DynamicEngineManager.extractTrendingMusicFeed(
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/trending") { w ->
    w.beginObject()
    w.contextField(ctx)
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
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/playlist") { w ->
    w.beginObject()
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
    w.contextField(ctx)
    w.endObject()
}

/** A channel detail page. [url] is an engine-composed "/channel/…" app link (an item's, a tab's, or
 *  a shelf CTA's), passed back verbatim — same contract as [extractPlaylistDetail]. */
suspend fun DynamicEngineManager.extractChannelDetail(
    url: String,
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/channel") { w ->
    w.beginObject()
    w.name("url").value(url)
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
    continuation: String? = null,
    ctx: YtContext = YtContext(),
): YtFeedPage = feedCommand("yt/next") { w ->
    w.beginObject()
    w.optionalField("videoId", videoId)
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

/**
 * What YouTube reports for THIS connection: its own IP-derived region and the language it served.
 * The app asserts nothing to get it, so it is the answer to "where does YouTube think we are" —
 * unlike [ytLocaleOptions], whose selection merely echoes what the request carried.
 */
suspend fun DynamicEngineManager.ytLocale(): YtLocales = use { engine ->
    engine.jsonCommand("yt/locale", { w ->
        w.beginObject()
        w.endObject()
    }) { readLocales(it) }
}

/**
 * Sync the user's locale OVERRIDE into the shared cookie identity (the legacy `YTPrefsUpdateTask`
 * role): `hl=`/`gl=` are written into the `PREF` cookie so cookie-identified page fetches — the
 * WebView player's embed page above all — follow the pick, and the sticky `s_gl` echo is deleted.
 * Blank means "automatic": the key is removed, so the bootstrap homepage answers with YouTube's
 * IP-derived region again. The engine also drops its bootstrapped session, so the next call
 * re-scrapes under the new identity — and it keeps the pair in memory (persisted to the host
 * Cache, from which a freshly-booted bundle re-seeds itself), re-asserting it on every cooked
 * YouTube request because the shared store's WebView pages can rewrite PREF underneath it.
 * Call ONLY when the user changes the pick — never on start; InnerTube calls assert the pair per
 * request regardless (this covers everything that does NOT).
 */
suspend fun DynamicEngineManager.applyYtLocale(hl: String, gl: String): Unit = use { engine ->
    engine.jsonCommand("yt/locale/apply", { w ->
        w.beginObject()
        w.name("hl").value(hl)
        w.name("gl").value(gl)
        w.endObject()
    }) { it.skipValue() }
}

/**
 * The content-language / content-region menus YouTube offers, labelled in [ctx]'s language. The
 * engine keeps ONE cached copy (in-memory + [ymusic.dynamicengine.CacheStore]) tagged with the
 * hl/gl it was fetched under: it re-fetches only when the asked pair changed or the copy is over a
 * week old, and a failed re-fetch falls back to the cached copy (possibly old or in the previous
 * language) instead of erroring. The detected hl/gl in the result stays live off the session,
 * never the cache.
 */
suspend fun DynamicEngineManager.ytLocaleOptions(ctx: YtContext = YtContext()): YtLocales = use { engine ->
    engine.jsonCommand("yt/locales", { w ->
        w.beginObject()
        w.contextField(ctx)
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
