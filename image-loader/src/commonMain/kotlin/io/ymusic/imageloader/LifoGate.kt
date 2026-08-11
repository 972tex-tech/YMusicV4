package io.ymusic.imageloader

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
            // NonCancellable: this cleanup must run even though the job is already cancelled, or the
            // dead waiter stays in the stack and a later release() grants a permit nobody consumes.
            withContext(NonCancellable) {
                lock.withLock { waiters.remove(waiter) }
                // The complete/completeExceptionally race decides permit ownership exactly once:
                // losing means release() already granted us its permit — pass it on.
                if (!waiter.completeExceptionally(e)) release()
            }
            throw e
        }
    }

    private suspend fun release() {
        while (true) {
            val next = lock.withLock {
                waiters.removeLastOrNull() ?: run {
                    free++
                    null
                }
            } ?: return
            // complete() losing to the waiter's own completeExceptionally means it cancelled after we
            // popped it — grant the permit to the next waiter instead.
            if (next.complete(Unit)) return
        }
    }
}
