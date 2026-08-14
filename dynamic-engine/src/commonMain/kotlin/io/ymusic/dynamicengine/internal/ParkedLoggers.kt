package io.ymusic.dynamicengine.internal

import kmpcommon.log.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The Kotlin callers' loggers a JS command can call back against: `jsonCommand` parks its caller's
 * logger for exactly the command's lifetime (key in the args head, removed in its finally), and a
 * host protocol resolves the callback head's key back to it — that join is what stitches one job's
 * trail across the pipe. A missing/absent/late key degrades to [root] (fire-and-forget JS jobs —
 * session bootstrap, crons — and callbacks racing the unpark), never an error.
 *
 * Deliberately unlocked: keys are unique via the atomic counter, entries are never iterated, and
 * this is logging-only data whose designed fallback IS the miss — a racy read just resolves to
 * [root]. No suspension anywhere, so unpark in a cancelled coroutine's finally always runs.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ParkedLoggers(val root: Logger?) {
    private val parked = HashMap<Int, Logger>()
    private val next = AtomicInt(0)

    fun park(logger: Logger): Int {
        val key = next.incrementAndFetch()
        parked[key] = logger
        return key
    }

    fun unpark(key: Int) {
        parked.remove(key)
    }

    /** The parked logger for [key], or [root] when it already unparked (a late callback). */
    fun resolve(key: Int): Logger? = parked[key] ?: root
}
