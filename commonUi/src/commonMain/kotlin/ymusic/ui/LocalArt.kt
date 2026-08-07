package ymusic.ui

import kmpffmpeg.metadata.FfmpegMetadataGetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import okio.Source
import okio.use
import ymusic.imageloader.ImageFetcher
import ymusic.imageloader.decodeScaled
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.MediaStoreItem

/**
 * `art://pic/<media-id>` — a local media file's artwork, extracted by ffmpeg at fetch time (no
 * separate image file exists): the embedded cover (attached_pic) when the file carries one, else a
 * representative decoded frame for a video. Keyed by the store id, which survives file moves (the
 * store preserves it), so the image caches stay warm across a rescan. [localArtUrl] mints the url
 * only when the store knows an artwork source exists, so art-less files never reach the fetcher
 * (every fetch spawns one ffmpeg).
 */
private const val ART_PIC = "art://pic/"

internal fun localArtUrl(item: MediaStoreItem): String? =
    if (item.thumbnailStreamIndex != null || item.videoCodec != null) ART_PIC + item.id else null

/** The `art:` resolver: media id → [store] item → [getter] extraction, decoded to the view size. */
fun localArtFetcher(
    getter: FfmpegMetadataGetter,
    store: BetterMediaStore,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { uri, size ->
    if (uri.scheme?.toString() != "art" || uri.authority?.toString() != "pic") return@ImageFetcher null
    val url = uri.toString()
    val id = uri.path.toString().substringAfterLast('/').toLongOrNull()
        ?: throw IOException("malformed art uri: $url")
    val item = store.findItem(id)
        ?: throw IOException("no media item for art uri: $url")
    val source = when {
        item.thumbnailStreamIndex != null -> getter.getAttachedPic(item.uri)
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
