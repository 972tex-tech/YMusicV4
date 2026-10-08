package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngineManager
import kmpcommon.log.Logger

/**
 * The music.youtube.com (WEB_REMIX) extraction surface — intent-level twins of the engine bundle's
 * `ytmusic/…` commands (engine-js `logic/yt/musicCommands.ts`). Same contract as [extractHomeFeed] &
 * friends (engine-composed app links round-tripped verbatim, opaque continuations, the user's input),
 * reusing the shared [feedCommand]/[readFeedPage] wire plumbing — only the source is the music client
 * (typed catalog, music renderers) instead of the WEB feed. A song's videoId still plays through the
 * WEB player path; this surface is catalog only.
 */

/** The Music Home feed (first page or a [YtFeedPage.continuation]). The engine owns the browseId
 *  (FEmusic_home) — the region's music home: regional charts / start-radio cold, personalized warmed. */
suspend fun DynamicEngineManager.extractMusicHome(
    continuation: String? = null,
    logger: Logger? = null,
    /** A user reload: re-scrape the session first. */
    refresh: Boolean = false,
): YtFeedPage = feedCommand("ytmusic/home", logger.cmd("extractMusicHome", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
    if (refresh) w.name("refresh").value(true)
}

/** The Music Explore feed (FEmusic_explore — new releases, moods & genres, charts entry points). */
suspend fun DynamicEngineManager.extractMusicExplore(
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand("ytmusic/explore", logger.cmd("extractMusicExplore", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
}

/** The Music New releases feed (FEmusic_new_releases — new albums & singles). */
suspend fun DynamicEngineManager.extractMusicNewReleases(
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand("ytmusic/new-releases", logger.cmd("extractMusicNewReleases", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
}

/** The Music Charts feed (FEmusic_charts — the region's charts, region from the engine locale). */
suspend fun DynamicEngineManager.extractMusicCharts(
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand("ytmusic/charts", logger.cmd("extractMusicCharts", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
}

/**
 * The signed-in Music Library (first page or a continuation). Fresh = the mixed landing (the user's
 * playlists, artists, profiles). A returned [YtFeedPage.chips] token narrows to one shelf type
 * (Playlists / Songs / Albums / …); a [YtFeedPage.sort] option token re-serves the page in that
 * order — both ride this same continuation slot, opaque to the app.
 */
suspend fun DynamicEngineManager.extractMusicLibrary(
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand("ytmusic/library", logger.cmd("extractMusicLibrary", "continuation" to continuation.crumbToken())) { w ->
    w.optionalField("continuation", continuation)
}

/**
 * A music detail page — album / artist / playlist. [url] is an engine-composed app link
 * ("/ytmusic/browse?id=<browseId>") from a music [YtFeedItem.url] or [YtSectionHeader.ctaDeeplink],
 * passed back VERBATIM; the engine decodes the browseId and serves it via the music client.
 */
suspend fun DynamicEngineManager.extractMusicBrowse(
    url: String,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "ytmusic/browse",
    logger.cmd("extractMusicBrowse", "url" to url, "continuation" to continuation.crumbToken()),
) { w ->
    w.name("url").value(url)
    w.optionalField("continuation", continuation)
}

/**
 * A music search (first page or a [YtFeedPage.continuation]). [params] filters to a result type
 * (Songs/Albums/Artists/Playlists) — an option's params from a returned [YtFilterOption]. The top
 * result card and the typed shelves come back as sections.
 */
suspend fun DynamicEngineManager.musicSearch(
    query: String? = null,
    params: String? = null,
    continuation: String? = null,
    logger: Logger? = null,
): YtFeedPage = feedCommand(
    "ytmusic/search",
    // query is user input — never a prop
    logger.cmd("musicSearch", "params" to params, "continuation" to continuation.crumbToken()),
) { w ->
    w.optionalField("query", query)
    w.optionalField("params", params)
    w.optionalField("continuation", continuation)
}

/** Music search autocomplete — the richer music/get_search_suggestions endpoint, flattened to query
 *  strings for the search box. */
suspend fun DynamicEngineManager.musicSearchSuggestions(query: String, logger: Logger? = null): List<String> =
    use { engine ->
        engine.jsonCommand("ytmusic/suggest", log = logger.cmd("musicSearchSuggestions"), writeFields = { w ->
            w.name("query").value(query)
        }) { readSuggestions(it) }
    }
