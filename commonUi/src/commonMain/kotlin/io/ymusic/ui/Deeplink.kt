package io.ymusic.ui

/**
 * An app-internal link the engine composed (a feed item's `url`, a channel tab's, a section
 * header's "View all"). The app ROUTES on the path prefix only — the whole link, query included,
 * round-trips VERBATIM to the engine (`extractPlaylistDetail` / `extractChannelDetail`), which is
 * free to stash private state in the query. Nothing here interprets ids, params or any YouTube
 * shape; that all lives in the remotely-updatable bundle.
 */
internal sealed interface Deeplink {
    val url: String

    data class Playlist(override val url: String) : Deeplink

    data class Channel(override val url: String) : Deeplink

    /** A generic feed page (the account's history / all-playlists) — no channel/playlist chrome. */
    data class Feed(override val url: String) : Deeplink
}

/** Route [link] to a detail surface; null = not a link this app version can open (an older app
 *  meeting a newer bundle's scheme stays inert, never crashes). */
internal fun parseDeeplink(link: String): Deeplink? = when {
    link.startsWith("/playlist?") -> Deeplink.Playlist(link)
    link.startsWith("/channel/") && link.length > "/channel/".length -> Deeplink.Channel(link)
    link.startsWith("/feed/") && link.length > "/feed/".length -> Deeplink.Feed(link)
    else -> null
}
