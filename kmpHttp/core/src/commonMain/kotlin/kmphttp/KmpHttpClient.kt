package kmphttp

import kmpcommon.log.Logger
import kmphttp.internal.DefaultIODispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface KmpHttpClient {
    val interceptors: List<AsyncInterceptor>
    val networkInterceptors: List<AsyncInterceptor>
    val cache: Cache?
    val cookieJar: CookieJar
    val sslConfig: SslConfig
    val defaultRedirectOptions: RedirectOptions
    val defaultExecuteTimeout: Duration?
    val defaultDispatcher: CoroutineDispatcher

    /** Client-internal diagnostics (retries, cache-journal faults, TLS config warnings) — never
     *  request bodies or cookie values. Null = off. */
    val logger: Logger?

    suspend fun execute(
        request: Request,
        options: RedirectOptions = defaultRedirectOptions,
        timeout: Duration? = defaultExecuteTimeout
    ): Response

    fun newBuilder(): BaseBuilder<out KmpHttpClient>

    abstract class BaseBuilder<T : KmpHttpClient> {
        var interceptors: MutableList<AsyncInterceptor>
        var networkInterceptors: MutableList<AsyncInterceptor>
        var cache: Cache?
        var cookieJar: CookieJar
        var sslConfig: SslConfig
        var defaultRedirectOptions: RedirectOptions
        var defaultExecuteTimeout: Duration?
        var defaultDispatcher: CoroutineDispatcher
        var logger: Logger?

        constructor() {
            this.interceptors = mutableListOf()
            this.networkInterceptors = mutableListOf()
            this.cache = null
            this.cookieJar = CookieJar.NO_COOKIES
            this.sslConfig = SslConfig()
            this.defaultRedirectOptions = RedirectOptions(
                followRedirects = true,
                followSslRedirects = true
            )
            this.defaultExecuteTimeout = 15.seconds
            this.defaultDispatcher = DefaultIODispatcher
            this.logger = null
        }

        constructor(copy: T) {
            this.interceptors = copy.interceptors.toMutableList()
            this.networkInterceptors = copy.networkInterceptors.toMutableList()
            this.cache = copy.cache
            this.cookieJar = copy.cookieJar
            this.sslConfig = copy.sslConfig
            this.defaultRedirectOptions = copy.defaultRedirectOptions
            this.defaultExecuteTimeout = copy.defaultExecuteTimeout
            this.defaultDispatcher = copy.defaultDispatcher
            this.logger = copy.logger
        }

        abstract fun build(): T
    }

    companion object
}

expect fun KmpHttpClient.Companion.Builder(): KmpHttpClient.BaseBuilder<*>

fun KmpHttpClient.Companion.create(configure: KmpHttpClient.BaseBuilder<*>.() -> Unit): KmpHttpClient {
    val builder = Builder()
    builder.configure()
    return builder.build()
}
