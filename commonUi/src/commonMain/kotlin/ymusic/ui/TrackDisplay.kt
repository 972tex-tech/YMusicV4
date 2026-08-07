package ymusic.ui

import ymusic.player.PlayableItem

/**
 * How a queue item renders in Now Playing — read straight off the [PlayableItem]'s own fields, no
 * intermediate model. The derivation (title/subtitle fallbacks, "artist · album") is the only
 * per-variant part; the layouts that use these are shared, so they live here as one `when` each.
 */

/** Display title: the item's own title, or a fallback (file name / video id). */
internal val PlayableItem.displayTitle: String
    get() = when (this) {
        is PlayableItem.LocalMedia -> title ?: uri.substringAfterLast('/')
        is PlayableItem.YtMedia -> title ?: videoId
    }

/** Display subtitle: "artist · album" for a local file, the channel for a YouTube video. */
internal val PlayableItem.displaySubtitle: String?
    get() = when (this) {
        is PlayableItem.LocalMedia -> listOfNotNull(artist, album).joinToString(" · ").ifEmpty { null }
        is PlayableItem.YtMedia -> artist
    }

/** Thumbnail url — an `art:<id>` for a local file, a network url for a YouTube video. */
internal val PlayableItem.displayThumbnailUrl: String?
    get() = when (this) {
        is PlayableItem.LocalMedia -> thumbnailUrl
        is PlayableItem.YtMedia -> thumbnailUrl
    }

/** Track length when the item carries it (YouTube items do; a local file's length comes from the player). */
internal val PlayableItem.displayDuration: String?
    get() = (this as? PlayableItem.YtMedia)?.duration
