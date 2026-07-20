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
 * The `art:` url family — artwork extracted FROM a local media file by ffmpeg (no separate image
 * file exists): `art://pic/<media-id>` is the embedded cover (attached_pic), `art://frame/<media-id>`
 * a representative decoded video frame. Keyed by the store id, which survives file moves (the store
 * preserves it), so the image caches stay warm across a rescan. [localArtUrl] mints the urls from
 * what the store already knows, so files without art never reach the fetcher (every fetch spawns
 * one ffmpeg).
 */
private const val ART_PIC = "art://pic/"
private const val ART_FRAME = "art://frame/"

internal fun localArtUrl(item: MediaStoreItem): String? = when {
    item.thumbnailStreamIndex != null -> ART_PIC + item.id
    item.videoCodec != null -> ART_FRAME + item.id
    else -> null
}

/** The `art:` resolver: media id → [store] uri → [getter] extraction, decoded to the view size. */
fun localArtFetcher(
    getter: FfmpegMetadataGetter,
    store: BetterMediaStore,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { url, _, size ->
    val pic = url.startsWith(ART_PIC)
    if (!pic && !url.startsWith(ART_FRAME)) return@ImageFetcher null
    val id = url.substringAfterLast('/').toLongOrNull()
        ?: throw IOException("malformed art url: $url")
    val item = store.findItem(id)
        ?: throw IOException("no media item for art url: $url")
    val source = if (pic) getter.getAttachedPic(item.uri) else getter.extractThumbnail(item.uri)
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
