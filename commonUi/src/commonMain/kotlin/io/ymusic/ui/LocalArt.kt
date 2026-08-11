package io.ymusic.ui

import io.ymusic.imageloader.ImageFetcher
import io.ymusic.imageloader.decodeScaled
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.model.MediaStoreItem
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
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
    if (uri.scheme?.toString() != "art" || uri.authority?.toString() != "pic") return@ImageFetcher null
    val url = uri.toString()
    val id = uri.path.toString().substringAfterLast('/').toLongOrNull()
        ?: throw IOException("malformed art uri: $url")
    val item = store.findItem(id)
        ?: throw IOException("no media item for art uri: $url")
    val youtubeVid = item.youtubeVid
    val source = when {
        item.thumbnailStreamIndex != null -> getter.getAttachedPic(item.uri)
        youtubeVid != null -> {
            val thumbUrl = youtubeThumbUrl(youtubeVid)
            return@ImageFetcher network.fetch(Uri.parse(thumbUrl), size)
                ?: throw IOException("network fetcher declined $thumbUrl for: $url")
        }
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
