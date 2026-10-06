package kmphttp

import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.time.Duration

/** The call didn't complete within the client's execute timeout — a transport failure like OkHttp's
 *  call timeout, so callers handle it with every other [IOException]. */
class HttpTimeoutException(message: String) : IOException(message)

/**
 * Runs [block] under the execute [timeout]. Expiry throws [HttpTimeoutException], not the
 * `TimeoutCancellationException` a bare `withTimeout` would: that is a `CancellationException`, which
 * callers read as "my coroutine was cancelled" and silently unwind instead of reporting a network
 * failure. `withTimeoutOrNull` converts only this deadline — an outer cancellation still propagates.
 */
internal suspend fun <T : Any> withExecuteTimeout(timeout: Duration?, block: suspend () -> T): T {
    if (timeout == null) return block()
    return withTimeoutOrNull(timeout) { block() } ?: throw HttpTimeoutException("no response within $timeout")
}
