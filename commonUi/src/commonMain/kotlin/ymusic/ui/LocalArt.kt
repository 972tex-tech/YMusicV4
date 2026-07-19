package ymusic.ui

import kmpcommon.uri.Uri
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
import ymusic.mediastore.model.MediaStoreItem

/**
 * The `art:` url family — artwork extracted FROM a local media file by ffmpeg (no separate image
 * file exists): `art://pic/<file-uri>` is the embedded cover (attached_pic), `art://frame/<file-uri>`
 * a representative decoded video frame. [localArtUrl] mints them from what the store already knows,
 * so files without art never reach the fetcher (every fetch spawns one ffmpeg).
 */
private const val ART_PIC = "art://pic/"
private const val ART_FRAME = "art://frame/"

internal fun localArtUrl(item: MediaStoreItem): String? = when {
    item.thumbnailStreamIndex != null -> ART_PIC + item.uri
    item.videoCodec != null -> ART_FRAME + item.uri
    else -> null
}

/** The `art:` resolver, on a shell-provided [getter]; register after the file/network fetchers. */
fun localArtFetcher(
    getter: FfmpegMetadataGetter,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { url, _, size ->
    val extract: suspend (Uri) -> Source
    val fileUri: String
    when {
        url.startsWith(ART_PIC) -> {
            extract = getter::getAttachedPic
            fileUri = url.removePrefix(ART_PIC)
        }
        url.startsWith(ART_FRAME) -> {
            extract = getter::extractThumbnail
            fileUri = url.removePrefix(ART_FRAME)
        }
        else -> return@ImageFetcher null
    }
    // ByteArray: the in-memory platform decoders behind decodeScaled are bytes-only (Skia codec),
    // and embedded art is small — the cap guards a mislabeled stream.
    val bytes = readCapped(extract(Uri.parse(fileUri)), maxImageBytes, url)
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
