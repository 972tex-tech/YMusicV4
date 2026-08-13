package io.ymusic.dynamicengine.internal

import kmpcommon.log.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The Kotlin callers' loggers a JS command can call back against: `jsonCommand` parks its caller's
 * logger for exactly the command's lifetime (key in the args head, removed in its finally), and a
 * host protocol resolves the callback head's key back to it — that join is what stitches one job's
 * trail across the pipe. A missing/absent/late key degrades to [root] (fire-and-forget JS jobs —
 * session bootstrap, crons — and callbacks racing the unpark), never an error.
 *
 * Plain synchronized, never a suspending Mutex: unpark runs in an already-cancelled coroutine's
 * finally, where a Mutex's prompt cancellation would skip the remove and leak the entry. The
 * lock exists only because park() has concurrent writers (commands launch from any dispatcher
 * thread): `++next` and a racing HashMap put need the fence; nanoseconds, uncontended.
 */
internal class ParkedLoggers(val root: Logger?) {
    private val lock = SynchronizedObject()
    private val parked = HashMap<Int, Logger>()
    private var next = 0

    fun park(logger: Logger): Int = synchronized(lock) {
        val key = ++next
        parked[key] = logger
        key
    }

    fun unpark(key: Int) {
        synchronized(lock) { parked.remove(key) }
    }

    fun resolve(key: Int?): Logger? = key?.let { synchronized(lock) { parked[it] } } ?: root
}
