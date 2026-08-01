package ymusic.ytmedia.proxy

import kmpcommon.io.AsyncSource
import kmpcommon.io.asAsyncSource
import kmpcommon.uri.PercentEncoder
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import okio.Buffer
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SabrServe
import ymusic.ytmedia.cache.serveProgressive
import ymusic.ytmedia.cache.serveSabr
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** One HTTP response the [MediaProxyServer] transport writes back to the WebView. [body] streams until EOF
 *  when [contentLength] is null (transport sends `Connection: close`). [onClose] releases upstream. */
class ProxyResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: AsyncSource,
    val contentLength: Long?,
    val onClose: () -> Unit = {},
)

/**
 * The platform-agnostic media proxy the WebView routes googlevideo requests through (see
 * `docs/player/youtube-playback.md`). webview-js rewrites a `/videoplayback` request into
 * `<proxy>/ymusicForward?url=…&method=…&body=<base64>`; [serve] reconstructs it, tries the player
 * [cache] first (a hit → synthesized bytes, zero network), and forwards a miss through [client].
 *
 * Transport is platform-specific ([MediaProxyServer]) — plain http on desktop/Android, https on iOS — but
 * this routing is common. The whole request (incl. the original method + body) is packed in the query, so
 * the transport only needs the request line; no request-body reading.
 */
@OptIn(ExperimentalEncodingApi::class)
class LocalMediaProxy(
    private val cache: MediaCache?,
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val log: (String) -> Unit = {},
) {
    suspend fun serve(target: String): ProxyResponse {
        if (target.startsWith(HEALTH_PATH)) return text(200, "OK", "ymusic-proxy")
        if (!target.startsWith(FORWARD_PATH)) return text(404, "Not Found", "no route")

        val params = parseQuery(target.substringAfter('?', ""))
        val url = params["url"] ?: return text(400, "Bad Request", "missing url")
        val method = params["method"] ?: "GET"
        val body = params["body"]?.let { runCatching { Base64.decode(it) }.getOrNull() }

        val c = cache
        if (c != null) {
            val hit = runCatching { serveFromCache(c, url, method, body) }.getOrElse { null }
            if (hit != null) return hit
        }
        return forward(url, method, body)
    }

    /** A cache hit → a synthesized response; null → miss (caller forwards to the network). */
    private suspend fun serveFromCache(cache: MediaCache, url: String, method: String, body: ByteArray?): ProxyResponse? {
        val isSabr = url.contains("sabr=1") && !url.contains("live=1") && !url.contains("noclen=1")
        if (isSabr && method == "POST" && body != null) {
            val request = VideoPlaybackAbrRequest.from(ProtoReader(body).readAllFields())
            when (val serve = cache.serveSabr(request)) {
                is SabrServe.Hit -> {
                    log("cache-hit sabr segments=${serve.segmentCount} bytes=${serve.cachedBytes}")
                    return umpResponse(serve.ump)
                }
                is SabrServe.Backoff -> return umpResponse(serve.ump)
                SabrServe.Miss -> return null
            }
        }
        // Progressive: itag+lmt+range in the query, no videoId. Serve the exact byte window if cached.
        if (url.contains("/videoplayback") && url.contains("clen=")) {
            val q = queryOf(url)
            val itag = q["itag"]?.toIntOrNull()
            val lmt = q["lmt"]?.toLongOrNull()
            val range = q["range"]?.split('-')
            if (itag != null && lmt != null && range?.size == 2) {
                val start = range[0].toLongOrNull()
                val end = range[1].toLongOrNull()
                if (start != null && end != null) {
                    val src = cache.serveProgressive(itag, lmt, start, end)
                    if (src != null) {
                        log("cache-hit progressive itag=$itag range=$start-$end")
                        val len = end - start + 1
                        return ProxyResponse(
                            status = 200, reason = "OK",
                            headers = listOf("Content-Type" to "application/octet-stream", "Content-Length" to "$len"),
                            body = src.asAsyncSource(), contentLength = len,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun umpResponse(ump: okio.Source): ProxyResponse {
        val len = (ump as? Buffer)?.size
        return ProxyResponse(
            status = 200, reason = "OK",
            headers = buildList {
                add("Content-Type" to "application/vnd.yt-ump")
                if (len != null) add("Content-Length" to "$len")
            },
            body = ump.asAsyncSource(), contentLength = len,
        )
    }

    private suspend fun forward(url: String, method: String, body: ByteArray?): ProxyResponse {
        val builder = RequestBuilder().url(url).header("user-agent", userAgent)
        if (body != null) builder.method(method, body.toRequestBody(null)) else builder.get()
        val response = client.execute(builder.build(), RedirectOptions(followRedirects = true, followSslRedirects = true))
        val length = response.body.contentLength().takeIf { it >= 0 }
        val headers = buildList {
            runCatching { response.body.contentType()?.toString() }.getOrNull()?.let { add("Content-Type" to it) }
            if (length != null) add("Content-Length" to "$length")
        }
        return ProxyResponse(response.code, "OK", headers, response.body.asyncSource(), length) { response.close() }
    }

    private fun text(status: Int, reason: String, body: String): ProxyResponse {
        val bytes = body.encodeToByteArray()
        return ProxyResponse(
            status, reason,
            listOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to "${bytes.size}"),
            Buffer().write(bytes).asAsyncSource(), bytes.size.toLong(),
        )
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i < 0) null else {
            val key = PercentEncoder.decode(pair.substring(0, i), plusIsSpace = true).toString()
            val value = PercentEncoder.decode(pair.substring(i + 1), plusIsSpace = true).toString()
            key to value
        }
    }.toMap()

    private fun queryOf(url: String): Map<String, String> = parseQuery(url.substringAfter('?', ""))

    companion object {
        const val FORWARD_PATH = "/ymusicForward"
        const val HEALTH_PATH = "/health"
    }
}
