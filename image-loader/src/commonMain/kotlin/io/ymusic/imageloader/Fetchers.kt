package io.ymusic.imageloader

import kmpcommon.io.AsyncSource
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
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
    return ImageFetcher { uri, size ->
        if (!uri.isHttp()) return@ImageFetcher null
        val url = uri.toString()
        val key = diskCacheKey(uri.cacheIdentity())
        cache.filePath(key)?.let { file ->
            try {
                return@ImageFetcher decodeScaledFile(fileSystem, file, size)
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
        decodeScaledFile(fileSystem, file, size)
    }
}

/**
 * http/https over [client] with no disk cache: the body is drained in memory under [maxImageBytes]
 * and decoded. Network caching, if any, is the client's `kmphttp.Cache`.
 */
fun networkFetcher(
    client: KmpHttpClient,
    maxImageBytes: Long = 32L * 1024 * 1024,
): ImageFetcher = ImageFetcher { uri, size ->
    if (!uri.isHttp()) return@ImageFetcher null
    val url = uri.toString()
    val bytes = executeChecked(client, url, maxImageBytes) { response ->
        readCapped(response.body.asyncSource(), maxImageBytes, url)
    }
    decodeScaled(bytes, size)
}

/**
 * `file://` urls straight from disk — no network, no copy; decoding happens from the file. The path is
 * percent-decoded from the uri, so an encoded name (`My%20Song.mp3`) resolves to the real file.
 */
fun fileFetcher(fileSystem: FileSystem = systemFileSystem): ImageFetcher = ImageFetcher { uri, size ->
    if (uri.scheme?.toString() != "file") return@ImageFetcher null
    decodeScaledFile(fileSystem, uri.filePath().toPath(), size)
}

private fun Uri.isHttp(): Boolean = scheme?.toString().let { it == "http" || it == "https" }

/** Stable disk-cache identity for a uri: drops volatile query params (ytimg's rotating ?sqp/&rs). */
private fun Uri.cacheIdentity(): String = UriBuilder(this).encodedQuery(null).buildString()

/** Executes [url], enforces success + the declared-length cap, and closes the response. */
private suspend fun <T> executeChecked(
    client: KmpHttpClient,
    url: String,
    maxImageBytes: Long,
    body: suspend (Response) -> T,
): T {
    val response = client.execute(RequestBuilder().url(url).build())
    try {
        if (!response.isSuccessful) throw IOException("image HTTP ${response.code}: $url")
        val declared = response.body.contentLength()
        if (declared > maxImageBytes) {
            throw IOException("image body $declared bytes exceeds the $maxImageBytes-byte cap: $url")
        }
        return body(response)
    } finally {
        response.close()
    }
}

/** Drains [source] with the cap re-checked as bytes arrive (Content-Length can lie or be absent). */
private suspend fun readCapped(source: AsyncSource, maxImageBytes: Long, url: String): ByteArray {
    val buffer = Buffer()
    while (true) {
        val n = source.read(buffer, SEGMENT)
        if (buffer.size > maxImageBytes) {
            throw IOException("image exceeds the $maxImageBytes-byte cap: $url")
        }
        if (n == -1L) break
    }
    return buffer.readByteArray()
}

private fun diskCacheKey(cacheKey: String): String = cacheKey.encodeUtf8().sha256().hex()

private const val SEGMENT = 64L * 1024
