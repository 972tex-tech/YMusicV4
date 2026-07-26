package ymusic.dynamicengine.internal

import kmpcommon.error.CodedError
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Wall-clock deadline for one host-store touch; a var only as a test seam. */
internal var hostStoreTimeoutMs: Long = 10_000L

/** A hop this slow gets a WARN even when it succeeds — the breadcrumb that localizes a recurrence
 *  of the zombie before it fully wedges. */
private const val SLOW_HOP_MS = 1_000L

// Deliberately detached and process-lifetime: a wedged store call is ABANDONED here at the
// deadline, not awaited — tying it to the caller's job would hand the wedge right back.
private val hostStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal class HostStoreTimeoutError(override val code: String, message: String) :
    Exception(message), CodedError {
    override val userMessage: String? = null
}

private class Box<T>(val value: T)

/**
 * Runs one host-store touch (cookie jar, cache store, settings store) OFF the engine thread with a
 * deadline.
 *
 * Host-protocol handlers run ON the engine dispatcher ([ymusic.quickjs.QuickJsRuntime]'s onOpen
 * launches them there), so a platform store that blocks — `android.webkit.CookieManager` waiting
 * out Chromium startup, SQLite behind a lock — used to block the ENGINE THREAD itself: every pipe,
 * timer and command wedged, no error anywhere (the "yt commands hang pre-HTTP" zombie). Two
 * properties fix that, and both are needed: the call must not execute on the engine thread, and a
 * call that never returns must not hold the command hostage — `withTimeout` alone cannot preempt
 * blocked code, so the call runs DETACHED and is abandoned at the deadline. The command then fails
 * fast with the typed [code]; if the abandoned call ever answers (or fails) late, that is logged as
 * the forensic breadcrumb saying which store wedged and for how long.
 */
internal suspend fun <T> hostStoreCall(
    logger: Logger?,
    logCtx: List<String>,
    code: String,
    what: String,
    block: suspend () -> T,
): T {
    val timeoutMs = hostStoreTimeoutMs
    val started = TimeSource.Monotonic.markNow()
    val call = hostStoreScope.async { block() }
    val boxed = withTimeoutOrNull(timeoutMs) { Box(call.await()) }
    if (boxed == null) {
        call.invokeOnCompletion { failure ->
            val elapsed = started.elapsedNow().inWholeMilliseconds
            logger?.log(
                Logger.Level.WARN,
                logCtx,
                "$what answered ${elapsed}ms after its ${timeoutMs}ms deadline" +
                    (failure?.let { " (failed: ${it.message})" } ?: ""),
                failure,
            )
        }
        throw HostStoreTimeoutError(code, "$what did not answer within ${timeoutMs}ms")
    }
    val elapsed = started.elapsedNow().inWholeMilliseconds
    if (elapsed >= SLOW_HOP_MS) {
        logger?.log(Logger.Level.WARN, logCtx, "slow host hop: $what took ${elapsed}ms")
    }
    return boxed.value
}
