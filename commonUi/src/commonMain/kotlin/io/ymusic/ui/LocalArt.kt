package io.ymusic.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.ymusic.imageloader.DecodedImage
import io.ymusic.imageloader.ImageFetcher
import io.ymusic.imageloader.decodeScaled
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import okio.Source
import okio.use

/**
 * `art://pic/<media-id>` — a local media file's artwork, the ONE url every consumer (Library,
 * NowPlaying, media session/Auto) carries for a library item. Resolved from the store row at fetch
 * time by [mediaStoreArtFetcher]: the embedded cover (attached_pic) when the file carries one,
 * else the YouTube thumbnail when the file is tagged with a video id, else a representative
 * decoded frame for a video. Keyed by the store id, which survives file moves (the store preserves
 * it), so the image caches stay warm across a rescan — and because the caches key on this single
 * url, the source choice runs once per cold miss, never per view. [localArtUrl] mints the url only
 * when the store knows an artwork source exists, so art-less files never reach the fetcher.
 */
fun localArtUrl(item: MediaStoreItem): String? =
    if (item.thumbnailStreamIndex != null || item.videoCodec != null || item.youtubeVid != null) {
        UriBuilder().scheme("art").authority("pic").appendPathSegment(item.id.toString()).buildString()
    } else {
        null
    }

/** The YouTube thumbnail for [videoId] — mqdefault (320×180): the one variant every video has,
 *  and its url carries no rotating query params, so it is stable enough for long-lived uris. */
fun youtubeThumbUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

/** `art://yt/<videoId>` — a video's best thumbnail at the view size, for a file not in the store. */
fun youtubeArtUrl(videoId: String): String =
    UriBuilder().scheme("art").authority("yt").appendPathSegment(videoId).buildString()

private const val MQDEFAULT_WIDTH = 320

/**
 * The thumbnail for [videoId] at [size]: [youtubeThumbUrl] when it covers the view, else sddefault
 * (640×480), falling back to hqdefault (480×360) since sddefault isn't generated for every upload.
 * Both are 4:3 frames with the 16:9 picture letterboxed inside, so their bars are trimmed.
 */
private suspend fun youtubeArt(network: ImageFetcher, videoId: String, size: IntSize, url: String): DecodedImage {
    val candidates = if (size.width > MQDEFAULT_WIDTH || size.height > MQDEFAULT_WIDTH * 9 / 16) {
        listOf(
            "https://i.ytimg.com/vi/$videoId/sddefault.jpg" to true,
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" to true,
        )
    } else {
        listOf(youtubeThumbUrl(videoId) to false)
    }
    var failure: IOException? = null
    for ((candidate, letterboxed) in candidates) {
        try {
            val decoded = network.fetch(Uri.parse(candidate), size) ?: continue
            return if (letterboxed) decoded.trimLetterbox() else decoded
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            failure = e
        }
    }
    throw IOException("no YouTube thumbnail for $videoId: $url", failure)
}

/** The 16:9 band of a 4:3 frame: the picture inside YouTube's letterboxed sddefault/hqdefault. */
private fun DecodedImage.trimLetterbox(): DecodedImage {
    val width = bitmap.width
    val height = width * 9 / 16
    if (height <= 0 || height >= bitmap.height) return this
    val band = ImageBitmap(width, height)
    Canvas(band).drawImageRect(
        bitmap,
        srcOffset = IntOffset(0, (bitmap.height - height) / 2),
        srcSize = IntSize(width, height),
        dstSize = IntSize(width, height),
        paint = Paint(),
    )
    return DecodedImage(band, downscaled)
}

/**
 * The `art:` resolver: media id → [store] item → artwork, decoded to the view size. Embedded
 * covers and video frames are ffmpeg-extracted via [getter] (every such fetch spawns one ffmpeg);
 * a file with no embedded cover but a YouTube video id delegates to [network] — the shells pass
 * the loader's own disk-cached network fetcher, so the thumb rides the normal
 * check-cache-then-http flow. That branch sits before the video-frame one: tagged videos show the
 * real poster rather than an arbitrary frame. `youtubeVid` is scan-validated to the 11-char id
 * alphabet, safe to interpolate.
 */
fun mediaStoreArtFetcher(
    getter: FfmpegMetadataGetter,
    store: BetterMediaStore,
    network: ImageFetcher,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { uri, size ->
    if (uri.scheme?.toString() != "art") return@ImageFetcher null
    val url = uri.toString()
    if (uri.authority?.toString() == "yt") {
        val videoId = uri.path.toString().substringAfterLast('/').takeIf { it.isNotEmpty() }
            ?: throw IOException("malformed art uri: $url")
        return@ImageFetcher youtubeArt(network, videoId, size, url)
    }
    if (uri.authority?.toString() != "pic") return@ImageFetcher null
    val id = uri.path.toString().substringAfterLast('/').toLongOrNull()
        ?: throw IOException("malformed art uri: $url")
    val item = store.findItem(id)
        ?: throw IOException("no media item for art uri: $url")
    val youtubeVid = item.youtubeVid
    val source = when {
        item.thumbnailStreamIndex != null -> getter.getAttachedPic(item.uri)
        youtubeVid != null -> return@ImageFetcher youtubeArt(network, youtubeVid, size, url)
        item.videoCodec != null -> getter.extractThumbnail(item.uri)
        else -> throw IOException("media item $id has no artwork source: $url")
    }
    // ByteArray: the in-memory platform decoders behind decodeScaled are bytes-only (Skia codec),
    // and embedded art is small — the cap guards a mislabeled stream.
    val bytes = readCapped(source, maxImageBytes, url)
    decodeScaled(bytes, size)
}

private suspend fun readCapped(source: Source, maxBytes: Long, url: String): ByteArray =
    withContext(Dispatchers.IO) {
        source.use { src ->
            val buffer = Buffer()
            while (true) {
                if (buffer.size > maxBytes) throw IOException("embedded art exceeds the $maxBytes-byte cap: $url")
                if (src.read(buffer, 64L * 1024) == -1L) break
            }
            buffer.readByteArray()
        }
    }
