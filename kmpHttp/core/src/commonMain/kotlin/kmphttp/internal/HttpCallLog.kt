package kmphttp.internal

import kmpcommon.log.Logger
import kmphttp.Request
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/** One request's log lines: a process-unique id and the elapsed time since it started. */
@OptIn(ExperimentalAtomicApi::class)
internal class HttpCallLog(private val logger: Logger, private val label: String) {
    private val id = nextId.incrementAndFetch()
    private val start = TimeSource.Monotonic.markNow()

    fun log(level: Logger.Level, stage: String, error: Throwable? = null) {
        logger.log(level, "http#$id $stage +${start.elapsedNow().inWholeMilliseconds}ms $label", error)
    }

    private companion object {
        val nextId = AtomicLong(0)
    }
}

/** Attaches [call] for the engine's network-stage events; engines without them return the request as is. */
internal expect fun Request.withCallLog(call: HttpCallLog): Request
