package ymusic.imageloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A semaphore whose waiters are served newest-first (a stack, not a queue) — the loader's priority
 * model: in a scrolling list the most recently requested images are the ones on screen, so they
 * jump ahead of stale requests still waiting. Cancelling a waiting acquirer removes it from the
 * stack (a scrolled-away item never occupies a slot).
 */
internal class LifoGate(permits: Int) {
    private val lock = Mutex()
    private var free = permits
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun acquire() {
        val waiter = lock.withLock {
            if (free > 0) {
                free--
                null
            } else {
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
        } ?: return
        try {
            waiter.await()
        } catch (e: CancellationException) {
            val gotPermit = lock.withLock {
                // Not in the stack anymore = release() already handed us its permit; pass it on.
                !waiters.remove(waiter) && waiter.isCompleted
            }
            if (gotPermit) withContext(NonCancellable) { release() }
            throw e
        }
    }

    private suspend fun release() {
        val next = lock.withLock {
            waiters.removeLastOrNull() ?: run {
                free++
                null
            }
        }
        next?.complete(Unit)
    }
}
