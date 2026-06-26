package kmphttp

import kmphttp.internal.AsyncChain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class OkKmpHttpClient : KmpHttpClient {
    override val interceptors: List<AsyncInterceptor>
    override val networkInterceptors: List<AsyncInterceptor>
    override val cache: Cache?
    override val cookieJar: CookieJar
    override val sslConfig: SslConfig
    override val defaultRedirectOptions: RedirectOptions
    override val defaultExecuteTimeout: Duration?
    override val defaultDispatcher: CoroutineDispatcher

    val okHttp: OkHttpClient

    /**
     * OkHttpClient variants keyed by redirect behaviour (see [redirectIndex]). All share the base
     * client's connection pool, dispatcher, cache, and TLS config via [OkHttpClient.newBuilder];
     * only the redirect flags differ. Lazily filled, guarded by [synchronized].
     */
    private val clients = arrayOfNulls<OkHttpClient>(3)

    constructor(builder: Builder) {
        this.interceptors = builder.interceptors.toList()
        this.networkInterceptors = builder.networkInterceptors.toList()
        this.cache = builder.cache
        this.cookieJar = builder.cookieJar
        this.sslConfig = builder.sslConfig
        this.defaultRedirectOptions = builder.defaultRedirectOptions
        this.defaultExecuteTimeout = builder.defaultExecuteTimeout
        this.defaultDispatcher = builder.defaultDispatcher

        this.okHttp = builder
            .okHttpBuilder
            .cache(builder.cache)
            .cookieJar(builder.cookieJar)
            .followRedirects(defaultRedirectOptions.followRedirects)
            .followSslRedirects(defaultRedirectOptions.followSslRedirects)
            .apply {
                sslConfig.applyTo(this)
                if (networkInterceptors().find { it is NetworkInterceptorBridge } == null) {
                    addNetworkInterceptor(NetworkInterceptorBridge())
                }
            }
            .build()

        // Seed the cache: the base client already uses the default redirect options.
        clients[redirectIndex(defaultRedirectOptions)] = okHttp
    }

    /**
     * Maps redirect options to a [clients] slot. `followSslRedirects` is irrelevant when redirects
     * are disabled, so the four flag combinations collapse to three distinct clients.
     */
    private fun redirectIndex(options: RedirectOptions): Int = when {
        !options.followRedirects -> 0
        !options.followSslRedirects -> 1
        else -> 2
    }

    private fun clientFor(options: RedirectOptions): OkHttpClient {
        val index = redirectIndex(options)
        clients[index]?.let { return it }
        return synchronized(clients) {
            clients[index] ?: okHttp.newBuilder()
                .followRedirects(options.followRedirects)
                .followSslRedirects(options.followSslRedirects)
                .build()
                .also { clients[index] = it }
        }
    }


    override suspend fun execute(
        request: Request,
        options: RedirectOptions,
        timeout: Duration?
    ): Response {
        return withContext(defaultDispatcher) {
            val asyncChain = AsyncChain(
                interceptors = interceptors,
                interceptorIndex = 0,
                execute = { beforeNetworkRequest ->
                    val finalOkHttpClient = clientFor(options)

                    suspendCancellableCoroutine { cont ->
                        val request =
                            if (networkInterceptors.isNotEmpty()) {
                                beforeNetworkRequest.newBuilder()
                                    .tag(OkKmpHttpClient::class, this@OkKmpHttpClient)
                                    .tag(RedirectOptions::class, options)
                                    .tag(Job::class, cont.context.job)
                                    .build()
                            } else {
                                beforeNetworkRequest
                            }
                        val call = finalOkHttpClient.newCall(request)
                        cont.invokeOnCancellation { call.cancel() }   // runs on the canceller's thread, mid-flight
                        try {
                            val resp = call.execute()                  // blocks THIS thread
                            cont.resume(resp) { _, _, _ -> resp.close() }
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                },
                request = request,
                options = options
            )
            if (timeout == null) {
                asyncChain.proceed(request)
            } else {
                withTimeout(timeout) {
                    asyncChain.proceed(request)
                }
            }
        }
    }

    override fun newBuilder(): Builder {
        return Builder(this)
    }

    class Builder : KmpHttpClient.BaseBuilder<OkKmpHttpClient> {
        internal val okHttpBuilder: OkHttpClient.Builder

        constructor(okHttpBuilder: OkHttpClient.Builder = OkHttpClient.Builder()) : super() {
            this.okHttpBuilder = okHttpBuilder
        }

        constructor(copy: OkKmpHttpClient) : super(copy) {
            this.okHttpBuilder = copy.okHttp.newBuilder()
        }

        override fun build(): OkKmpHttpClient {
            return OkKmpHttpClient(this)
        }
    }

    private class NetworkInterceptorBridge : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val okKmp = request.tag(OkKmpHttpClient::class) ?: return chain.proceed(request)
            val redirectOptions =
                request.tag(RedirectOptions::class) ?: return chain.proceed(request)
            if (okKmp.networkInterceptors.isEmpty()) return chain.proceed(request)
            val parent = request.tag(Job::class) ?: return chain.proceed(request)
            return runBlocking(parent) {
                AsyncChain(
                    okKmp.networkInterceptors, 0,
                    { finalRequest -> chain.proceed(finalRequest) },
                    request, redirectOptions
                ).proceed(request)
            }
        }
    }
}

@Suppress("FunctionName")
actual fun KmpHttpClient.Companion.Builder(): KmpHttpClient.BaseBuilder<*> {
    return OkKmpHttpClient.Builder()
}