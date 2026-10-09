package kmphttp

import kmpcommon.log.Logger
import kmphttp.internal.HttpCallLog
import kmphttp.internal.withCallLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Logs each request at INFO: `http#<id> start` and then `http#<id> <code> (<protocol>) +<ms>ms`,
 * `failed` or `canceled`, each line suffixed with method, host and path. No query, headers,
 * cookies or bodies are logged. On JVM, OkHttp's network stages (connecting, TLS, sent) join the
 * same id at DEBUG, so a hung request shows how far it got.
 */
class HttpLogInterceptor(private val logger: Logger) : AsyncInterceptor {
    override suspend fun intercept(chain: AsyncInterceptor.Chain): Response {
        val request = chain.request
        val call = HttpCallLog(logger, "${request.method} ${request.url.host}${request.url.encodedPath}")
        call.log(Logger.Level.INFO, "start")
        val response = try {
            chain.proceed(request.withCallLog(call))
        } catch (e: CancellationException) {
            call.log(Logger.Level.INFO, "canceled")
            throw e
        } catch (e: Throwable) {
            call.log(Logger.Level.INFO, "failed", e)
            throw e
        }
        call.log(Logger.Level.INFO, "${response.code} (${response.protocol})")
        return response
    }
}
