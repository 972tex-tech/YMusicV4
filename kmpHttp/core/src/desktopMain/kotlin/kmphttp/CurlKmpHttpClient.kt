package kmphttp

import kmphttp.cache.CacheInterceptor
import kmphttp.curl.CurlMultiApi
import kmphttp.internal.AsyncChain
import kmphttp.internal.http.BridgeInterceptor
import kmphttp.internal.http.FollowUpInterceptor
import kmphttp.internal.http.RetryOnConnectionFailureInterceptor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import libcurl.CURL_GLOBAL_DEFAULT
import libcurl.curl_global_init
import kotlin.time.Duration

/**
 * libcurl-backed [KmpHttpClient] for desktop-native targets (Linux/macOS).
 *
 * The user [interceptors] run first, then the built-in redirect/cookie/cache chain
 * (FollowUp → Bridge → Cache) and [networkInterceptors], before the libcurl network call (driven by
 * [CurlExecutor] over the shared [CurlMultiApi] event loop).
 *
 * Construct via [CurlKmpHttpClient.Builder]; on macOS this is an explicit alternative to the
 * NSURLSession default, so it does not provide the [KmpHttpClient.Companion.Builder] platform
 * default there (it is the default on Linux).
 *
 * [SslConfig] (custom trust anchors, certificate pinning, and PEM client certificates) is applied to
 * each request's curl handle (see [applySslConfig]).
 */
@OptIn(ExperimentalForeignApi::class)
class CurlKmpHttpClient(builder: Builder) : KmpHttpClient {
    override val interceptors: List<AsyncInterceptor> = builder.interceptors.toList()
    override val networkInterceptors: List<AsyncInterceptor> = builder.networkInterceptors.toList()
    override val cache: Cache? = builder.cache
    override val cookieJar: CookieJar = builder.cookieJar
    override val sslConfig: SslConfig = builder.sslConfig
    override val defaultRedirectOptions: RedirectOptions = builder.defaultRedirectOptions
    override val defaultExecuteTimeout: Duration? = builder.defaultExecuteTimeout
    override val defaultDispatcher: CoroutineDispatcher = builder.defaultDispatcher
    override val logger: kmpcommon.log.Logger? = builder.logger

    /** The shared curl multi handle (its own event-loop worker), reused across [newBuilder]. */
    val curlMultiApi: CurlMultiApi = builder.curlMultiApi ?: CurlMultiApi()

    private val userAgent: String = "kmpHttp/${KmpHttp.VERSION} (libcurl)"

    /** [sslConfig] pre-digested into curl's byte forms once; reused across all requests. */
    private val curlSslConfig = CurlSslConfig(sslConfig)

    init {
        curl_global_init(CURL_GLOBAL_DEFAULT.convert())
        cache?.logger = logger
        sslConfig.warnIfClientCertsUnsupported("CurlKmpHttpClient", logger) {
            it is SslConfig.ClientCert.Pem
        }
    }

    override suspend fun execute(
        request: Request,
        options: RedirectOptions,
        timeout: Duration?
    ): Response = withContext(defaultDispatcher) {
        val chain = AsyncChain(
            interceptors = buildList {
                addAll(interceptors)
                add(RetryOnConnectionFailureInterceptor(logger = logger))
                add(FollowUpInterceptor())
                add(BridgeInterceptor(cookieJar, userAgent))
                add(CacheInterceptor(cache))
                addAll(networkInterceptors)
            },
            interceptorIndex = 0,
            execute = { req -> CurlExecutor(req, curlMultiApi, curlSslConfig).execute() },
            request = request,
            options = options
        )
        withExecuteTimeout(timeout) { chain.proceed(request) }
    }

    override fun newBuilder(): Builder = Builder(this)

    class Builder : KmpHttpClient.BaseBuilder<CurlKmpHttpClient> {
        /** Optional shared curl multi handle; a dedicated one is created when null. */
        var curlMultiApi: CurlMultiApi? = null

        constructor() : super()

        constructor(copy: CurlKmpHttpClient) : super(copy) {
            this.curlMultiApi = copy.curlMultiApi
        }

        override fun build(): CurlKmpHttpClient = CurlKmpHttpClient(this)
    }
}
