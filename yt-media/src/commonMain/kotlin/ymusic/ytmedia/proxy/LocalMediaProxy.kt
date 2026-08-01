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
import ymusic.ytmedia.cache.BlobWriter
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SabrServe
import ymusic.ytmedia.cache.serveProgressive
import ymusic.ytmedia.cache.serveSabr
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import ymusic.ytmedia.sabr.formatCacheKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One response the platform adapter writes back to the WebView. [body] streams until EOF (bytes pulled in a
 * loop by the adapter); [contentLength] is null when unknown. [url] is the effective request URL (the final
 * one after any followed redirect) — carried so an adapter can surface it. [onClose] releases upstream.
 */
class ProxyResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: AsyncSource,
    val contentLength: Long?,
    val url: String,
    val onClose: () -> Unit = {},
)

/**
 * The platform-agnostic media router behind the player cache (see `docs/player/youtube-playback.md`).
 * webview-js turns a googlevideo `/videoplayback` request into `/ymusicForward?url=…&method=…&body=<base64>`
 * (the original method + body packed in the query); [serve] reconstructs it, tries the player [cache]
 * first (a hit → synthesized bytes, zero network), and on a miss forwards through [client] **while teeing
 * the response back into the cache** so a later request is a hit.
 *
 * This is the **shared** half. Delivery is per-platform and calls straight into [serve] — native request
 * interception on desktop (`CefResourceHandler`) and Android (`shouldInterceptRequest`), and the JS↔native
 * message channel on iOS (`WKScriptMessageHandlerWithReply`). Each adapter pumps [ProxyResponse.body] into
 * its platform response type. No socket/loopback server involved. The player cache is a hard dependency —
 * when there is no cache there is nothing to serve, so the interception adapter simply isn't installed.
 *
 * **[client] must share the WebView's cookie store** (VISITOR_INFO1_LIVE / YSC / consent, and the matching
 * User-Agent): googlevideo + BotGuard reject a forward whose cookies/UA don't match the WebView's own
 * requests (see the "request headers must be consistent" rule). Wire it with the platform cookie jar
 * (`CefCookieManager` on desktop, `CookieManager` on Android, `WKHTTPCookieStore` on iOS).
 */
@OptIn(ExperimentalEncodingApi::class)
class LocalMediaProxy(
    private val cache: MediaCache,
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

        val hit = runCatching { serveFromCache(url, method, body) }.getOrElse { null }
        return hit ?: forward(url, method, body)
    }

    /** A cache hit → a synthesized response; null → miss (forward to the network). */
    private suspend fun serveFromCache(url: String, method: String, body: ByteArray?): ProxyResponse? {
        if (isSabr(url) && method == "POST" && body != null) {
            val request = VideoPlaybackAbrRequest.from(ProtoReader(body).readAllFields())
            when (val serve = cache.serveSabr(request)) {
                is SabrServe.Hit -> {
                    log("cache-hit sabr segments=${serve.segmentCount} bytes=${serve.cachedBytes}")
                    return umpResponse(serve.ump, url)
                }
                is SabrServe.Backoff -> return umpResponse(serve.ump, url)
                SabrServe.Miss -> return null
            }
        }
        val prog = progressiveRange(url) ?: return null
        val src = cache.serveProgressive(prog.itag, prog.lmt, prog.start, prog.end) ?: return null
        log("cache-hit progressive itag=${prog.itag} range=${prog.start}-${prog.end}")
        val len = prog.end - prog.start + 1
        return ProxyResponse(
            status = 200, reason = "OK",
            headers = listOf("Content-Type" to "application/octet-stream", "Content-Length" to "$len"),
            body = src.asAsyncSource(), contentLength = len, url = url,
        )
    }

    private fun umpResponse(ump: okio.Source, url: String): ProxyResponse {
        val len = (ump as? Buffer)?.size
        return ProxyResponse(
            status = 200, reason = "OK",
            headers = buildList {
                add("Content-Type" to "application/vnd.yt-ump")
                if (len != null) add("Content-Length" to "$len")
            },
            body = ump.asAsyncSource(), contentLength = len, url = url,
        )
    }

    /** Miss → fetch from the network, teeing the body back into the cache as it streams to the WebView. */
    private suspend fun forward(url: String, method: String, body: ByteArray?): ProxyResponse {
        val builder = RequestBuilder().url(url).header("user-agent", userAgent)
        if (body != null) builder.method(method, body.toRequestBody(null)) else builder.get()
        val response = client.execute(builder.build(), RedirectOptions(followRedirects = true, followSslRedirects = true))
        val length = response.body.contentLength().takeIf { it >= 0 }
        val headers = buildList {
            runCatching { response.body.contentType()?.toString() }.getOrNull()?.let { add("Content-Type" to it) }
            if (length != null) add("Content-Length" to "$length")
        }
        // Fill on the way through: SABR responses are a UMP part stream (tee each complete segment into
        // the cache while passing the raw bytes to the WebView untouched); progressive responses are a
        // plain byte range (tee at the range's known offset). Both leave the response bytes unchanged.
        val networkBody = response.body.asyncSource()
        val effectiveUrl = response.request.url.toString()
        val teed = when {
            isSabr(url) && method == "POST" && response.isSuccessful -> SabrCacheTee(networkBody, cache, log)
            else -> fillProgressive(effectiveUrl, networkBody) ?: networkBody
        }
        return ProxyResponse(response.code, "OK", headers, teed, length, url = effectiveUrl) { response.close() }
    }

    /** If [url] is a progressive range read, wrap [network] so its bytes also land in the cache. */
    private fun fillProgressive(url: String, network: AsyncSource): AsyncSource? {
        val prog = progressiveRange(url) ?: return null
        val writer = cache.blobs.writer(formatCacheKey(prog.itag, prog.lmt), prog.start, prog.end - prog.start + 1)
        return CachingSource(network, writer)
    }

    private class Progressive(val itag: Int, val lmt: Long, val start: Long, val end: Long)

    private fun progressiveRange(url: String): Progressive? {
        if (!url.contains("/videoplayback") || !url.contains("clen=")) return null
        val q = queryOf(url)
        val itag = q["itag"]?.toIntOrNull() ?: return null
        val lmt = q["lmt"]?.toLongOrNull() ?: return null
        val range = q["range"]?.split('-') ?: return null
        val start = range.getOrNull(0)?.toLongOrNull() ?: return null
        val end = range.getOrNull(1)?.toLongOrNull() ?: return null
        return Progressive(itag, lmt, start, end)
    }

    private fun isSabr(url: String) =
        url.contains("sabr=1") && !url.contains("live=1") && !url.contains("noclen=1")

    private fun text(status: Int, reason: String, body: String): ProxyResponse {
        val bytes = body.encodeToByteArray()
        return ProxyResponse(
            status, reason,
            listOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to "${bytes.size}"),
            Buffer().write(bytes).asAsyncSource(), bytes.size.toLong(), url = "",
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

/**
 * Tees a network body into a [BlobWriter] as it streams through: each read mirrors the freshly-read bytes
 * to the cache, EOF commits, an early [close] aborts (dropping only the uncommitted trailing block —
 * committed blocks stay, per MediaBlobCache's per-block commit). [BlobWriter.commit]/[abort] are
 * non-suspend, so [close] releases the region cleanly without a coroutine.
 */
private class CachingSource(
    private val network: AsyncSource,
    private val writer: BlobWriter,
) : AsyncSource {
    private var finished = false

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish(commit = true)
            return -1L
        }
        val copy = Buffer()
        sink.copyTo(copy, sink.size - n, n) // the bytes just appended to sink
        writer.sink.write(copy, n)
        return n
    }

    override fun close() {
        network.close()
        finish(commit = false)
    }

    private fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        if (commit) writer.commit() else writer.abort()
    }
}
