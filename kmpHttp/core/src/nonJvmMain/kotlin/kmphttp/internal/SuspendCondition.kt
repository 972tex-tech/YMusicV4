package kmphttp.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A condition variable for suspending/resuming coroutines.
 * Used to coordinate between curl callbacks and coroutine reads.
 */
@OptIn(ExperimentalAtomicApi::class)
class SuspendCondition {
    companion object {
        private const val STATE_WORKING = 0
        private const val STATE_PAUSED = 1
        private const val STATE_RELEASED = 2
    }

    private val waiters = mutableListOf<CancellableContinuation<Unit>>()
    private val waitersLock = SynchronizedObject()
    private val state = AtomicInt(STATE_WORKING)

    suspend fun await() {
        if (state.load() != STATE_PAUSED) return

        suspendCancellableCoroutine { continuation ->
            synchronized(waitersLock) {
                if (state.load() != STATE_PAUSED) {
                    continuation.resume(Unit) { _, _, _ -> }
                    return@suspendCancellableCoroutine
                }
                waiters.add(continuation)
            }
            continuation.invokeOnCancellation {
                synchronized(waitersLock) {
                    waiters.remove(continuation)
                }
            }
        }
    }

    fun pause() {
        state.compareAndSet(STATE_WORKING, STATE_PAUSED)
    }

    fun resume() {
        if (state.compareAndSet(STATE_PAUSED, STATE_WORKING)) {
            synchronized(waitersLock) {
                waiters.forEach { it.resume(Unit) { _, _, _ -> } }
                waiters.clear()
            }
        }
    }

    fun release() {
        if (state.exchange(STATE_RELEASED) != STATE_RELEASED) {
            synchronized(waitersLock) {
                waiters.forEach { it.resume(Unit) { _, _, _ -> } }
                waiters.clear()
            }
        }
    }
}
