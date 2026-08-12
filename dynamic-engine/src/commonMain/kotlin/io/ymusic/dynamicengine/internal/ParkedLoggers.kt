package io.ymusic.dynamicengine.internal

import kmpcommon.log.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Kotlin callers' loggers a JS command can call back against: `jsonCommand` parks its caller's
 * logger for exactly the command's lifetime (key in the args head, removed in its finally), and a
 * host protocol resolves the callback head's key back to it — that join is what stitches one job's
 * trail across the pipe. A missing/absent/late key degrades to [root] (fire-and-forget JS jobs —
 * session bootstrap, crons — and callbacks racing the unpark), never an error.
 */
internal class ParkedLoggers(val root: Logger?) {
    private val mutex = Mutex()
    private val parked = HashMap<Int, Logger>()
    private var next = 0

    suspend fun park(logger: Logger): Int = mutex.withLock {
        val key = ++next
        parked[key] = logger
        key
    }

    suspend fun unpark(key: Int) {
        mutex.withLock { parked.remove(key) }
    }

    suspend fun resolve(key: Int?): Logger? = key?.let { mutex.withLock { parked[it] } } ?: root
}
