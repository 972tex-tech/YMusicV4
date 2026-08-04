package ymusic.ytmedia.proxy

import kmpcommon.io.AsyncSource
import kmpcommon.io.asAsyncSource
import kmpcommon.log.Logger
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
import kotlin.time.TimeSource

/**
 * One response the platform adapter writes back to the WebView. [body] streams until EOF (bytes pulled in a
 * loop by the adapter); [contentLength] is null when unknown. [url] is the effective request URL (the final
 * one after any followed redirect) — carried so an adapter can surface it.
 *
 * **The consumer owns [body] and must close it** when done: that releases the upstream network response —
 * the SABR tee / progressive caching source forward `close()` down to it, and a cache-hit body has no
 * upstream to release. There is no separate teardown hook.
 */
class ProxyResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: AsyncSource,
    val contentLength: Long?,
    val url: String,
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
    private val logger: Logger? = null,
    /** Per-request/part chatter (cache hit-miss decisions, fill sizes, UMP parts). Toggle at runtime;
     *  WARN-level events (cache-serve faults, SABR errors, protection rejects) always log if [logger] is set. */
    var verbose: Boolean = false,
    /** Serve SABR from cache (synthesized UMP hits). A synthesized hit omits the control parts a real
     *  response carries — `FORMAT_INITIALIZATION_METADATA`, `STREAM_PROTECTION_STATUS` — that the player
     *  needs to (re)initialize a format, so a hit on a quality / audio-only switch can wedge the player
     *  (the blank-video hack relies on the native format-switch path). Set false to fill-only (always
     *  forward SABR; the tee still caches) until the hit builder replays those control parts. */
    var serveSabrHits: Boolean = true,
) {
    /** Emit a DEBUG line only in [verbose] mode; the message is built lazily so it's free when off. */
    private inline fun logVerbose(message: () -> String) {
        if (verbose) logger?.log(Logger.Level.DEBUG, LOG_CTX, message())
    }

    /** One-line dump of what the player is asking for — the state the serve decision is made against.
     *  `vid` tells main-video vs ad; empty `selV` (or tracks=1) means audio-only; `buffered` is what the
     *  player claims it already has (drives HIT vs BACKOFF). */
    private fun sabrRequestSummary(r: VideoPlaybackAbrRequest): String {
        val abr = r.clientAbrState
        val buffered = r.bufferedRanges.joinToString(",") {
            "${it.formatId.itag}@${it.startMs}-${it.endMs}ms[seg${it.startSegmentIndex}-${it.endSegmentIndex}]"
        }
        return "sabr-req vid=${r.videoId} t=${abr.playerTimeMs}ms tracks=${abr.enabledTrackTypesBitfield} " +
            "selA=${r.selectedAudioFormats.map { it.itag }} selV=${r.selectedVideoFormats.map { it.itag }} " +
            "avail=${r.adaptiveFormats.size} buffered=[$buffered]"
    }

    suspend fun serve(target: String): ProxyResponse {
        if (target.startsWith(HEALTH_PATH)) return text(200, "OK", "ymusic-proxy")
        if (!target.startsWith(FORWARD_PATH)) return text(404, "Not Found", "no route")

        val params = parseQuery(target.substringAfter('?', ""))
        val url = params["url"] ?: return text(400, "Bad Request", "missing url")
        val method = params["method"] ?: "GET"
        val body = params["body"]?.let { runCatching { Base64.decode(it) }.getOrNull() }
        return serveRequest(url, method, body)
    }

    /**
     * Serve one reconstructed request: a cache hit → synthesized bytes (zero network), a miss →
     * forwarded through [client] and teed back into the cache. The native-interception adapters reach
     * this via [serve] (parsing the `/ymusicForward` query); the iOS message-channel adapter, which
     * receives the url/method/body as fields, calls it directly.
     */
    suspend fun serveRequest(url: String, method: String, body: ByteArray?): ProxyResponse {
        // A cache-serve fault must never fail the request — fall through to the network — but it's a real
        // bug (bad parse, cache read), so log it as a breadcrumb rather than swallowing it silently.
        val hit = runCatching { serveFromCache(url, method, body) }
            .onFailure { logger?.log(Logger.Level.WARN, LOG_CTX, "cache serve failed, forwarding: $url", it) }
            .getOrNull()
        return hit ?: forward(url, method, body)
    }

    /** A cache hit → a synthesized response; null → miss (forward to the network). Logs the decision and
     *  how long it took: HIT / BACKOFF / MISS with the reason and, for a progressive miss, how much of the
     *  range *was* cached (so a partial-coverage miss is visible). */
    private fun serveFromCache(url: String, method: String, body: ByteArray?): ProxyResponse? {
        val started = TimeSource.Monotonic.markNow()
        if (isSabr(url) && method == "POST" && body != null) {
            val request = VideoPlaybackAbrRequest.from(ProtoReader(body).readAllFields())
            logVerbose { sabrRequestSummary(request) }
            if (!serveSabrHits) return null // fill-only: forward SABR to the network (still teed into cache)
            when (val serve = cache.serveSabr(request)) {
                is SabrServe.Hit -> {
                    logVerbose { "HIT sabr: served=[${serve.servedRanges}] (${serve.segmentCount} segments, ${serve.cachedBytes} bytes, built in ${started.elapsedNow()})" }
                    return umpResponse(serve.ump, url)
                }
                is SabrServe.Backoff -> {
                    logVerbose { "BACKOFF sabr: requester buffered ahead — told to back off (${started.elapsedNow()})" }
                    return umpResponse(serve.ump, url)
                }
                is SabrServe.Miss -> {
                    logVerbose { "MISS sabr: ${serve.reason} (${started.elapsedNow()})" }
                    return null
                }
            }
        }
        val prog = progressiveRange(url) ?: return null
        val len = prog.end - prog.start + 1
        val src = cache.serveProgressive(prog.itag, prog.lmt, prog.start, prog.end)
        if (src == null) {
            logVerbose {
                val cached = cache.blobs.cachedLength(formatCacheKey(prog.itag, prog.lmt), prog.start, len).coerceAtLeast(0)
                "MISS progressive itag=${prog.itag} range=${prog.start}-${prog.end}: cached $cached/$len bytes (${started.elapsedNow()})"
            }
            return null
        }
        logVerbose { "HIT progressive itag=${prog.itag} range=${prog.start}-${prog.end} (${started.elapsedNow()})" }
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
        logVerbose { "cache-miss → network ${response.code}: $effectiveUrl" }
        if (!response.isSuccessful) {
            return ProxyResponse(response.code, "OK", headers, networkBody, length, url = effectiveUrl)
        }
        if (isSabr(url)) {
            return ProxyResponse(response.code, "OK", headers, SabrCacheTee(networkBody, cache, logger, verbose), length, url = effectiveUrl)
        }
        return ProxyResponse(response.code, "OK", headers, fillProgressive(effectiveUrl, networkBody) ?: networkBody, length, url = effectiveUrl)
    }

    /**
     * If [url] is a progressive range read, wrap [network] so its bytes also land in the cache — but only
     * the part that isn't cached yet. The WebView asked for the whole `[start, end]`, so we forward and
     * pass the whole body through untouched; the writer, though, opens at the first uncached byte (skipping
     * the already-cached leading prefix `cachedLength` reports). Re-teeing a cached prefix would only spill
     * it to a temp and drop it at commit — pointless disk churn. Interior holes past the prefix are still
     * covered (the per-block commit dedupe drops any block that already exists).
     */
    private fun fillProgressive(url: String, network: AsyncSource): AsyncSource? {
        val prog = progressiveRange(url) ?: return null
        val key = formatCacheKey(prog.itag, prog.lmt)
        val length = prog.end - prog.start + 1
        val skip = cache.blobs.cachedLength(key, prog.start, length).coerceAtLeast(0) // cached leading prefix
        if (skip >= length) return network // whole range cached (a serve race lost it) — nothing to write
        val writer = cache.blobs.writer(key, prog.start + skip, length - skip)
        return CachingSource(network, writer, skip) { teed ->
            logVerbose { "progressive-fill itag=${prog.itag} $teed bytes @ ${prog.start + skip} (skipped $skip cached)" }
        }
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
        private val LOG_CTX = listOf("mediaproxy")
    }
}

/**
 * Tees a network body into a [BlobWriter] as it streams through: each read mirrors the freshly-read bytes
 * to the cache, EOF commits, an early [close] aborts (dropping only the uncommitted trailing block —
 * committed blocks stay, per MediaBlobCache's per-block commit). [BlobWriter.commit]/[abort] are
 * non-suspend, so [close] releases the region cleanly without a coroutine.
 *
 * [skip] is a count of leading network bytes that are already cached: they pass through to the reader
 * untouched but are **not** teed (the writer starts at the first uncached byte). Once consumed, every
 * subsequent byte is mirrored.
 */
internal class CachingSource(
    private val network: AsyncSource,
    private val writer: BlobWriter,
    private var skip: Long = 0L,
    private val onCommit: (teed: Long) -> Unit = {},
) : AsyncSource {
    private var finished = false
    private var teed = 0L // bytes actually mirrored into the cache (excludes the skipped cached prefix)

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        val n = network.read(sink, byteCount)
        if (n == -1L) {
            finish(commit = true)
            return -1L
        }
        var teeOffset = sink.size - n // the bytes just appended to sink
        var teeLen = n
        if (skip > 0) {
            val drop = minOf(skip, teeLen)
            teeOffset += drop
            teeLen -= drop
            skip -= drop
        }
        if (teeLen > 0) {
            val copy = Buffer()
            sink.copyTo(copy, teeOffset, teeLen)
            writer.sink.write(copy, teeLen)
            teed += teeLen
        }
        return n
    }

    override fun close() {
        network.close()
        finish(commit = false)
    }

    private fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        if (commit) {
            writer.commit()
            onCommit(teed)
        } else {
            writer.abort()
        }
    }
}
