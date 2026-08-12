package io.ymusic.dynamicengine

import kmphttp.AsyncInterceptor
import kmphttp.Cache
import kmphttp.CookieJar
import kmphttp.HeadersBuilder
import kmphttp.InMemoryCookieJar
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
import kotlin.time.Duration

/** A canned [KmpHttpClient] for tests. [handler] builds a response (and can suspend or throw);
 *  the last [execute] arguments are recorded for assertions. */
class FakeHttpClient(
    private val handler: suspend (Request) -> Response,
) : KmpHttpClient {
    constructor(canned: CannedResponse) : this({ request -> canned.build(request) })

    /** Settable so cookie tests can back the client with a real (or throwing) jar. */
    override var cookieJar: CookieJar = InMemoryCookieJar()

    var lastRequest: Request? = null
        private set
    var requestCount: Int = 0
        private set
    var lastOptions: RedirectOptions? = null
        private set
    var lastTimeout: Duration? = null
        private set

    override val interceptors: List<AsyncInterceptor> = emptyList()
    override val networkInterceptors: List<AsyncInterceptor> = emptyList()
    override val cache: Cache? = null
    override val sslConfig: SslConfig = SslConfig()
    override val defaultRedirectOptions: RedirectOptions =
        RedirectOptions(followRedirects = true, followSslRedirects = true)
    override val defaultExecuteTimeout: Duration? = null
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val logger: kmpcommon.log.Logger? = null

    override suspend fun execute(request: Request, options: RedirectOptions, timeout: Duration?): Response {
        lastRequest = request
        requestCount++
        lastOptions = options
        lastTimeout = timeout
        return handler(request)
    }

    override fun newBuilder(): KmpHttpClient.BaseBuilder<out KmpHttpClient> =
        throw UnsupportedOperationException("FakeHttpClient has no builder")
}

/** A canned response whose body streams [chunks] one read at a time; [closed] records that the
 *  body was released. [hangAfterChunks] = the read after the last chunk stalls until cancelled or
 *  closed (a dead origin); [failAfterChunks] = it throws IOException("origin died") instead (a
 *  connection lost mid-body). [finalUrl] simulates a redirect (Response.request.url differs). */
class CannedResponse(
    private val status: Int = 200,
    private val message: String = "OK",
    private val headers: List<Pair<String, String>> = emptyList(),
    private val chunks: List<ByteArray> = emptyList(),
    private val hangAfterChunks: Boolean = false,
    private val failAfterChunks: Boolean = false,
    private val finalUrl: String? = null,
) {
    private val flag = ClosedFlag()
    val closed: Boolean get() = flag.closed

    fun build(request: Request): Response = ResponseBuilder()
        .request(finalUrl?.let { request.newBuilder().url(it).build() } ?: request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message(message)
        .headers(
            // addUnsafeNonAscii: fabricate what a lenient/hostile origin could emit (e.g. CR/LF
            // in a value) — the validating add() would reject it before it ever hit our wire
            HeadersBuilder().apply {
                for ((name, value) in headers) addUnsafeNonAscii(name, value)
            }.build(),
        )
        .body(fakeResponseBody(chunks, hangAfterChunks, failAfterChunks, flag))
        .build()

    companion object {
        /** A response whose body is [text] split into [chunkSize]-byte chunks (may split code points). */
        fun ofText(
            text: String,
            status: Int = 200,
            headers: List<Pair<String, String>> = emptyList(),
            chunkSize: Int = Int.MAX_VALUE,
        ): CannedResponse {
            val bytes = text.encodeToByteArray()
            val chunks = if (chunkSize >= bytes.size || bytes.isEmpty()) {
                listOf(bytes)
            } else {
                bytes.toList().chunked(chunkSize).map { it.toByteArray() }
            }
            return CannedResponse(status = status, headers = headers, chunks = chunks)
        }
    }
}

class ClosedFlag {
    var closed = false
}

/** Platform bodies differ (okhttp3 vs the nonJvm AsyncSource shape) — built per platform. */
expect fun fakeResponseBody(
    chunks: List<ByteArray>,
    hangAfterChunks: Boolean,
    failAfterChunks: Boolean,
    flag: ClosedFlag,
): ResponseBody

/** Read a request body's content (platform write/read APIs differ). */
expect suspend fun RequestBody.readAll(): ByteArray

/** A [CookieJar] whose every operation throws — exercises the cookie protocol's `E20041` jar-failure
 *  path. Built per platform because `CookieJar` is blocking on JVM (OkHttp) and suspend elsewhere. */
expect fun throwingCookieJar(): CookieJar

/** A jar whose reads NEVER answer — the CookieManager-wedged-against-Chromium-startup shape. */
expect fun wedgedCookieJar(): CookieJar
