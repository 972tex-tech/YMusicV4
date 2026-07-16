package ymusic.dynamicengine.yt

/**
 * A feed entry scraped by the engine bundle — the Kotlin mirror of the `WireFeedItem` shape
 * (engine-js `logic/yt/wire.ts`). A **generic display component**, not a typed record: the UI renders
 * [title] + [subtitleLine1]/[subtitleLine2] + an optional [ctaText] verbatim and decides nothing about
 * what text they hold — the engine composes those lines (it alone knows whether line 1 should be the
 * channel, the view count, the subscriber total…). [kind] is an open string (see [KIND_VIDEO]…) that
 * picks the thumbnail shape + which structural badge overlays; a newer remote bundle can introduce
 * kinds an older app ignores gracefully.
 *
 * Read off the pipe by [readFeedPage] (YtFeedReader.kt), not a reflective deserializer — hence no
 * `@Serializable` on this or the other result shapes.
 */
data class YtFeedItem(
    val kind: String,
    /** videoId / playlistId / channelId, by [kind]. */
    val id: String,
    val title: String,
    /** Engine-composed subtitle lines, rendered verbatim (no app-side field selection). */
    val subtitleLine1: String? = null,
    val subtitleLine2: String? = null,
    /** Optional action label (e.g. "Subscribe" on a channel); null = no CTA. */
    val ctaText: String? = null,
    /** Channel-link target: tapping the byline/avatar navigates here. */
    val channelId: String? = null,
    val thumb: String? = null,
    /** Thumbnail badge overlays (structural, fixed position — not subtitle text). */
    val duration: String? = null,
    val itemCount: String? = null,
    val live: Boolean = false,
) {
    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_CHANNEL = "channel"
    }
}

/** A feed filter chip (home "All / Music / Gaming…"); [token] re-requests the filtered feed. */
data class YtChip(
    val title: String,
    val token: String? = null,
    val selected: Boolean = false,
)

/**
 * The single-line header above a [YtSection] (design: `[circle icon] · title · [count] · [CTA]`).
 * Every part is optional — a bare grid with no header omits it entirely.
 */
data class YtSectionHeader(
    val title: String? = null,
    val iconThumb: String? = null,
    val count: String? = null,
    val ctaText: String? = null,
    val ctaToken: String? = null,
)

/**
 * A run of items under one header. [layout] is the engine's INTENT (see [LAYOUT_LIST]/[LAYOUT_GRID],
 * open string for a future carousel) derived from the source renderer; the app may still re-resolve
 * it responsively by screen width. The app renders each item with the (`item.kind` × [layout]) leaf
 * component — videoType{List,Grid} / channelType{List,Grid} / playlistType{List,Grid}.
 */
data class YtSection(
    val header: YtSectionHeader? = null,
    val layout: String = LAYOUT_LIST,
    val items: List<YtFeedItem>,
    val collapsible: Boolean = false,
    val continuation: String? = null,
) {
    companion object {
        const val LAYOUT_LIST = "list"
        const val LAYOUT_GRID = "grid"
    }
}

/** One page of any feed surface (browse / search / watch-next); [continuation] fetches the next. */
data class YtFeedPage(
    val sections: List<YtSection>,
    val continuation: String? = null,
    val chips: List<YtChip> = emptyList(),
    /** Watch-next only: the video autoplay would play next. */
    val autoplayVideoId: String? = null,
)

/** Localization + visitor identity carried on every InnerTube call (docs/innertube/README.md). */
data class YtContext(
    val hl: String = "en",
    val gl: String = "US",
    val visitorData: String? = null,
)
