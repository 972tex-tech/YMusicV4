package io.ymusic.imageloader

import kmpcommon.io.AsyncSource
import kmpcommon.uri.Uri
import kmpcommon.uri.filePath
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.Response
import kmphttp.asyncSource
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.use

/**
 * http/https over [client] with the downloaded originals owned on disk (the Coil/Glide model): the
 * body streams into a journaled LRU cache under [cacheDir] — the compressed image never sits in
 * the heap whole — and decoding happens from the cached FILE, keyed by url identity so all display
 * sizes share one download. Give [client] NO http cache (double storage otherwise). [maxImageBytes]
 * caps a single body (a mislinked huge file must fail, not fill the disk). A cached file that
 * vanishes mid-decode (eviction race) is re-downloaded once; one that decodes as garbage fails
 * as-is — re-downloading would fetch the same bytes on every load until LRU evicts it.
 */
fun networkFetcher(
    client: KmpHttpClient,
    fileSystem: FileSystem,
    cacheDir: Path,
    cacheMaxBytes: Long = 128L * 1024 * 1024,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher {
    val cache = ImageSourceCache(fileSystem, cacheDir, cacheMaxBytes)
    return ImageFetcher { uri, size, scale ->
        if (!uri.isHttp()) return@ImageFetcher null
        val url = uri.toString()
        val key = diskCacheKey(imageCacheKey(url))
        cache.filePath(key)?.let { file ->
            try {
                return@ImageFetcher decodeChecked(url) { decodeScaledFile(fileSystem, file, size, scale) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Still on disk but undecodable = permanent; vanished = eviction race, refetch.
                if (fileSystem.exists(file)) throw e
            }
        }
        val file = executeChecked(client, url, maxImageBytes) { response ->
            cache.write(key, response.body.asyncSource(), maxImageBytes, url)
        }
        decodeChecked(url) { decodeScaledFile(fileSystem, file, size, scale) }
    }
}

/**
 * http/https over [client] with no disk cache: the body is drained in memory under [maxImageBytes]
 * and decoded. Network caching, if any, is the client's `kmphttp.Cache`.
 */
fun networkFetcher(
    client: KmpHttpClient,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { uri, size, scale ->
    if (!uri.isHttp()) return@ImageFetcher null
    val url = uri.toString()
    val bytes = executeChecked(client, url, maxImageBytes) { response ->
        readCapped(response.body.asyncSource(), maxImageBytes, url)
    }
    decodeChecked(url) { decodeScaled(bytes, size, scale) }
}

/**
 * `file://` urls straight from disk — no network, no copy; decoding happens from the file. The path is
 * percent-decoded from the uri, so an encoded name (`My%20Song.mp3`) resolves to the real file.
 */
fun fileFetcher(fileSystem: FileSystem = systemFileSystem): ImageFetcher = ImageFetcher { uri, size, scale ->
    if (uri.scheme?.toString() != "file") return@ImageFetcher null
    val path = uri.filePath().toPath()
    if (!fileSystem.exists(path)) throw ImageLoadException(ImageLoadException.Reason.NotFound, "no such file: $uri")
    decodeChecked(uri.toString()) { decodeScaledFile(fileSystem, path, size, scale) }
}

/** Runs a platform decode, classifying an undecodable image (the decoders throw IllegalArgumentException). */
suspend fun decodeChecked(url: String, decode: suspend () -> DecodedImage): DecodedImage =
    try {
        decode()
    } catch (e: IllegalArgumentException) {
        throw ImageLoadException(ImageLoadException.Reason.Undecodable, "undecodable image: $url", e)
    }

private fun Uri.isHttp(): Boolean = scheme?.toString().let { it == "http" || it == "https" }

/**
 * A stable cache identity for an image [url]: the url minus its rotating signature params. Only
 * those go — other params select the image (ytimg's `sqp` encodes the size and crop), so two sizes
 * of one image never share an entry.
 */
fun imageCacheKey(url: String): String {
    val query = url.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return url
    val kept = query.split('&').filter { it.substringBefore('=') !in ROTATING_QUERY_PARAMS }
    val base = url.substringBefore('?')
    return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
}

/** ytimg's `rs` is a per-response signature over the other params; it changes, the image doesn't. */
private val ROTATING_QUERY_PARAMS = setOf("rs")

/** Executes [url], enforces success + the declared-length cap, and closes the response. */
private suspend fun <T> executeChecked(
    client: KmpHttpClient,
    url: String,
    maxImageBytes: Long,
    body: suspend (Response) -> T,
): T {
    val response = try {
        client.execute(RequestBuilder().url(url).build())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw ImageLoadException(ImageLoadException.Reason.Network, "image request failed: $url", e)
    }
    return response.use {
        if (!response.isSuccessful) {
            val reason = if (response.code == 404 || response.code == 410) {
                ImageLoadException.Reason.NotFound
            } else {
                ImageLoadException.Reason.Network
            }
            throw ImageLoadException(reason, "image HTTP ${response.code}: $url")
        }
        val declared = response.body.contentLength()
        if (declared > maxImageBytes) {
            throw ImageLoadException(
                ImageLoadException.Reason.Undecodable,
                "image body $declared bytes exceeds the $maxImageBytes-byte cap: $url",
            )
        }
        try {
            body(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageLoadException) {
            throw e
        } catch (e: IOException) {
            throw ImageLoadException(ImageLoadException.Reason.Network, "image download failed: $url", e)
        }
    }
}

/** Drains [source] with the cap re-checked as bytes arrive (Content-Length can lie or be absent). */
private suspend fun readCapped(source: AsyncSource, maxImageBytes: Long, url: String): ByteArray {
    val buffer = Buffer()
    while (true) {
        val n = source.read(buffer, SEGMENT)
        if (buffer.size > maxImageBytes) {
            throw ImageLoadException(ImageLoadException.Reason.Undecodable, "image exceeds the $maxImageBytes-byte cap: $url")
        }
        if (n == -1L) break
    }
    return buffer.readByteArray()
}

private fun diskCacheKey(cacheKey: String): String = cacheKey.encodeUtf8().sha256().hex()

private const val SEGMENT = 64L * 1024
