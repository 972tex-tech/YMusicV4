package io.ymusic.ytmedia.proxy

import io.ymusic.ytmedia.sabr.ADAPTIVE_FORMATS_HEADER
import kmpcommon.io.AsyncSource
import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.HeadersBuilder
import kmphttp.KmpHttpClient
import kmphttp.Protocol
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.RequestBody
import kmphttp.Response
import kmphttp.ResponseBody
import kmphttp.ResponseBuilder
import kmphttp.SslConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.time.Duration

/**
 * Routes cacheable googlevideo SABR POSTs — the ones engine-js tagged with [ADAPTIVE_FORMATS_HEADER] —
 * through [LocalMediaProxy] (cache HIT/BACKOFF/tee). Everything else goes straight to [inner], with
 * the header removed since it must never reach googlevideo. [LocalMediaProxy.client] must be [inner],
 * not this wrapper, or a miss recurses.
 */
class SabrProxyHttpClient(
    private val inner: KmpHttpClient,
    private val proxy: LocalMediaProxy?,
) : KmpHttpClient {
    override val interceptors: List<AsyncInterceptor> get() = inner.interceptors
    override val networkInterceptors: List<AsyncInterceptor> get() = inner.networkInterceptors
    override val cache: Cache? get() = inner.cache
    override val cookieJar: CookieJar get() = inner.cookieJar
    override val sslConfig: SslConfig get() = inner.sslConfig
    override val defaultRedirectOptions: RedirectOptions get() = inner.defaultRedirectOptions
    override val defaultExecuteTimeout: Duration? get() = inner.defaultExecuteTimeout
    override val defaultDispatcher: CoroutineDispatcher get() = inner.defaultDispatcher
    override val logger: kmpcommon.log.Logger? get() = inner.logger

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        val url = request.url.toString()
        if (!isSabrPost(url, request.method) || request.header(ADAPTIVE_FORMATS_HEADER) == null) {
            return inner.execute(request, options, timeout)
        }
        if (proxy == null) {
            return inner.execute(request.newBuilder().removeHeader(ADAPTIVE_FORMATS_HEADER).build(), options, timeout)
        }
        val body = request.body?.let { readRequestBody(it) }
        val headers = request.headers.toList()
        // Callers include the QuickJS engine thread; the cache serve is blocking SQLite + disk.
        val pr = withContext(Dispatchers.IO) { proxy.serveRequest(url, request.method, body, headers) }
        val finalUrl = pr.url.ifEmpty { url }
        val built = if (finalUrl == url) request else request.newBuilder().url(finalUrl).build()
        return ResponseBuilder()
            .request(built)
            .protocol(Protocol.HTTP_1_1)
            .code(pr.status)
            .message(pr.reason)
            .headers(
                HeadersBuilder().apply {
                    for ((name, value) in pr.headers) add(name, value)
                }.build(),
            )
            .body(proxyResponseBody(pr.body, pr.headers, pr.contentLength))
            .build()
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> = inner.newBuilder()

    companion object {
        fun isSabrPost(url: String, method: String): Boolean =
            method.equals("POST", ignoreCase = true) &&
                url.contains("/videoplayback") &&
                url.contains("sabr=1")
    }
}

internal expect suspend fun readRequestBody(body: RequestBody): Buffer

internal expect fun proxyResponseBody(
    source: AsyncSource,
    headers: List<Pair<String, String>>,
    contentLength: Long?,
): ResponseBody
