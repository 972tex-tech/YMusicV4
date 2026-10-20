package io.ymusic.ui

import androidx.compose.ui.unit.IntSize
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.imageloader.ImageRequest
import io.ymusic.imageloader.ImageSource
import io.ymusic.imageloader.SourceCrop
import io.ymusic.imageloader.imageCacheKey

/** One image identity per video, shared by feed thumbs, the queue, Now Playing and local art. */
internal fun youtubeImageKey(videoId: String): String = "yt:$videoId"

/** The video id of an `i.ytimg.com/vi/<id>/…` (or `vi_webp`) thumbnail url, else null. */
internal fun youtubeVideoIdOf(url: String): String? = YTIMG_VIDEO.find(url)?.groupValues?.get(1)

/**
 * The thumbnails every video has, by size: mqdefault (320×180, 16:9), hqdefault (480×360) and
 * sddefault (640×480) — the last two 4:3 frames with the picture letterboxed, trimmed to 16:9.
 * sddefault isn't generated for every upload; the loader's variant index remembers which miss.
 */
internal fun youtubeThumbnailSources(videoId: String): List<ImageSource> = listOf(
    ImageSource(youtubeThumbUrl(videoId), 320, 180),
    ImageSource("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", 480, 360, SourceCrop.Letterbox16x9),
    ImageSource("https://i.ytimg.com/vi/$videoId/sddefault.jpg", 640, 480, SourceCrop.Letterbox16x9),
)

/**
 * Identity + sources for a single art url: a YouTube video thumbnail becomes its video's identity
 * with the standard sizes (the given url's own size is unknown, so it isn't added — any crop it
 * names is one of these pictures); anything else is the url alone.
 */
internal fun artRequestSources(url: String): Pair<String, List<ImageSource>> {
    googleArtSources(url)?.let { return it }
    val videoId = youtubeVideoIdOf(url) ?: return imageCacheKey(url) to listOf(ImageSource(url))
    return youtubeImageKey(videoId) to youtubeThumbnailSources(videoId)
}

/** Now Playing's art floor and the system artwork size (notification, session, Android Auto): one
 *  size for both, so a phone fetches and decodes a song's art once for the two. */
const val NOW_PLAYING_ART_PX = 640

/** A single art url as an [ImageRequest] for a [width]×[height] box, with the variants
 *  [artRequestSources] derives — for surfaces outside Compose (notification, widget, Android Auto). */
fun artImageRequest(url: String, width: Int, height: Int): ImageRequest {
    val (key, sources) = artRequestSources(url)
    return ImageRequest(key, sources, IntSize(width, height))
}

/**
 * Identity + sources for an engine thumbnail list: a video's list joins its video identity (with
 * the standard sizes added), each entry keeping its declared size; other lists (avatars, playlist
 * art) are keyed by their largest url.
 */
internal fun thumbRequestSources(thumbs: List<YtThumb>): Pair<String, List<ImageSource>>? {
    if (thumbs.isEmpty()) return null
    val listed = thumbs.map { ImageSource(it.url, it.w ?: 0, it.h ?: 0) }
    val largest = thumbs.largestThumbUrl() ?: thumbs.last().url
    googleArtSources(largest)?.let { (key, sized) -> return key to (sized + listed).distinctBy { it.url } }
    val videoId = thumbs.firstNotNullOfOrNull { youtubeVideoIdOf(it.url) }
        ?: return imageCacheKey(largest) to listed
    return youtubeImageKey(videoId) to youtubeThumbnailSources(videoId) + listed
}

/**
 * Identity + sizes for Google image-server art (`…googleusercontent.com/…=wN-hN…`, YT Music): the
 * server scales the same image to any `=wN-hN` (capped at the original), so one url — whatever size
 * it names — offers every view its fit. Null for any other url.
 */
internal fun googleArtSources(url: String): Pair<String, List<ImageSource>>? {
    val match = GOOGLE_SIZED_ART.find(url) ?: return null
    val (prefix, width, middle, height, rest) = match.destructured
    val w = width.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val h = height.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val sources = GOOGLE_ART_SIZES.map { size ->
        val sizedHeight = (size.toLong() * h / w).toInt()
        ImageSource("$prefix$size$middle$sizedHeight$rest", size, sizedHeight)
    }
    return "$prefix$middle$rest" to sources
}

private val GOOGLE_ART_SIZES = listOf(120, 240, 480, 720)

private val GOOGLE_SIZED_ART = Regex("""^(https?://[^/]+\.googleusercontent\.com/[^=]+=w)(\d+)(.*?-h)(\d+)(.*)$""")

private val YTIMG_VIDEO = Regex("""^https?://i\d*\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""")
