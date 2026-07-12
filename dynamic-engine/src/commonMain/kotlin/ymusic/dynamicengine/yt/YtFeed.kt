package ymusic.dynamicengine.yt

import kotlinx.serialization.Serializable

/**
 * A feed entry scraped by the engine bundle — the Kotlin mirror of the `WireFeedItem` shape
 * (engine-js `logic/yt/wire.ts`). [kind] is an open string (see [KIND_VIDEO]…) so a newer remote
 * bundle can introduce kinds an older app ignores gracefully.
 */
@Serializable
data class YtFeedItem(
    val kind: String,
    /** videoId / playlistId / channelId, by [kind]. */
    val id: String,
    val title: String,
    val byline: String? = null,
    val channelId: String? = null,
    val thumb: String? = null,
    val duration: String? = null,
    val views: String? = null,
    val published: String? = null,
    val live: Boolean = false,
    val itemCount: String? = null,
    val subscribers: String? = null,
) {
    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_CHANNEL = "channel"
    }
}

/** A feed filter chip (home "All / Music / Gaming…"); [token] re-requests the filtered feed. */
@Serializable
data class YtChip(
    val title: String,
    val token: String? = null,
    val selected: Boolean = false,
)

/** One page of any feed surface (browse / search / watch-next); [continuation] fetches the next. */
@Serializable
data class YtFeedPage(
    val items: List<YtFeedItem>,
    val continuation: String? = null,
    val chips: List<YtChip> = emptyList(),
    /** Watch-next only: the video autoplay would play next. */
    val autoplayVideoId: String? = null,
)

/** Localization + visitor identity carried on every InnerTube call (docs/innertube/README.md). */
@Serializable
data class YtContext(
    val hl: String = "en",
    val gl: String = "US",
    val visitorData: String? = null,
)
