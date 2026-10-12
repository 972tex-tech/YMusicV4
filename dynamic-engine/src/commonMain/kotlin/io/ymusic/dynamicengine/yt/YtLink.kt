package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.DynamicEngineManager
import kmpcommon.log.Logger

/** What an outside link opens ([resolveLink]). */
sealed interface YtLinkTarget {
    /** Play [item]; [playlistUrl] is its playlist's app link when the link named one. */
    data class Video(val item: YtFeedItem, val playlistUrl: String?, val startMs: Long?) : YtLinkTarget

    /** The playlist detail behind the app link [url] ([extractPlaylistDetail]). */
    data class Playlist(val url: String) : YtLinkTarget

    /** The channel detail behind the app link [url] ([extractChannelDetail]). */
    data class Channel(val url: String) : YtLinkTarget

    /** The music detail behind the app link [url] ([extractMusicBrowse]). */
    data class MusicBrowse(val url: String) : YtLinkTarget

    data class Search(val query: String) : YtLinkTarget
}

/**
 * The app target behind an outside [url] the app was opened with — a youtube.com (any host),
 * youtu.be or music.youtube.com link, or the same path under the app's own scheme/domains. Which url
 * shapes exist (and resolving a handle to its channel) is the engine's call; null = nothing this app
 * opens (or a kind this app version doesn't know).
 */
suspend fun DynamicEngineManager.resolveLink(url: String, logger: Logger? = null): YtLinkTarget? = use { engine ->
    engine.jsonCommand(
        "yt/resolve-link",
        { w -> w.name("url").value(url) },
        // the url may carry a search query (user input) — never a prop
        logger.cmd("resolveLink"),
    ) { readLinkTarget(it) }
}
