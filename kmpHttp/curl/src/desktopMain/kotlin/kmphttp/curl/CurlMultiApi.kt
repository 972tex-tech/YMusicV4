/*
 * Copyright (C) 2025 YMusic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kmphttp.curl

import kmpcommon.LinkedList
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import libcurl.CURLMSG
import libcurl.CURLM_OK
import libcurl.curl_multi_cleanup
import libcurl.curl_multi_info_read
import libcurl.curl_multi_init
import libcurl.curl_multi_perform
import libcurl.curl_multi_poll
import libcurl.curl_multi_strerror
import libcurl.curl_multi_wakeup
import okio.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi

/**
 * Type alias for curl multi handle.
 */
@OptIn(ExperimentalForeignApi::class)
typealias MultiHandle = CPointer<*>

private sealed interface EventLoopAction {
    class Add(val easyHandle: EasyHandle) : EventLoopAction
    class Remove(val easyHandle: EasyHandle, val onRemoved: (() -> Unit)?) : EventLoopAction
    class Unpause(val easyHandle: EasyHandle) : EventLoopAction
}

/**
 * Thread-safe manager for curl multi interface.
 *
 * Provides async HTTP operations using libcurl's multi interface:
 * - Thread-safe add/cancel of jobs (easy handles)
 * - Background event loop using curl_multi_poll
 * - Automatic connection pooling (handled by libcurl)
 * - Pause/resume support for flow control
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)
class CurlMultiApi(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private enum class State {
        NOT_STARTED,
        RUNNING,
        CLOSING,
        CLOSED
    }

    private val multiHandle: MultiHandle = curl_multi_init()
        ?: throw IOException("Failed to initialize curl multi handle")

    private var multiHandleFailure: IOException? = null

    private val lock = SynchronizedObject()

    // Active jobs being processed by the multi handle
    // no need to guard active jobs, guaranteed to be accessed only from the event loop thread
    private val activeJobs = mutableMapOf<EasyHandle, CompletableDeferred<UInt>>()

    // Pending operations to be processed on the event loop thread
    private val suspendCondition = SuspendCondition()
    private val pendingEventLoopActions = LinkedList<EventLoopAction>()

    private var state = AtomicReference<State>(State.NOT_STARTED)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var startedJob: Job? = null

    /**
     * Start the event loop. Must be called before adding jobs.
     */
    fun start() {
        synchronized(lock) {
            if (state.load() >= State.CLOSING) throw IllegalStateException(
                "CurlMultiApi is closed",
                multiHandleFailure
            )
            if (state.compareAndSet(State.NOT_STARTED, State.RUNNING)) {
                startedJob = scope.launch {
                    eventLoop()
                }
            }
        }
    }

    /**
     * Add a job (EasyHandle) to be processed.
     * Thread-safe - can be called from any thread.
     * Must call [removeJob] when it's done.
     *
     * @param easyHandle The configured curl easy handle
     * @return CompletableDeferred<Int> that can be awaited for curl result code.
     */
    fun addJob(easyHandle: EasyHandle): Deferred<UInt> {
        synchronized(lock) {
            check(!activeJobs.containsKey(easyHandle)) { "This easy handle $easyHandle is already added" }
            val completion = CompletableDeferred<UInt>()
            activeJobs[easyHandle] = completion

            if (state.load() == State.CLOSED) {
                completion.completeExceptionally(
                    multiHandleFailure ?: IllegalStateException("CurlMultiApi is closed")
                )
                return completion
            }

            pendingEventLoopActions.add(EventLoopAction.Add(easyHandle))
            wakeup()
            return completion
        }
    }

    /**
     * Remove (and cancel) an EasyHandle added via [addJob].
     * Thread-safe - can be called from any thread.
     *
     * @param easyHandle The easy handle to cancel
     * @param cause The cancellation reason
     */
    /**
     * @param onRemoved Runs once the handle is actually cleaned up (off the multi handle). Until
     * then the event loop can still fire this handle's callbacks, so anything their userdata
     * points at (StableRefs!) must only be disposed from here — disposing before queueing the
     * removal is a use-after-free on the event-loop thread.
     */
    fun removeAndCleanUpJob(
        easyHandle: EasyHandle,
        cause: Throwable? = null,
        onRemoved: (() -> Unit)? = null
    ) {
        synchronized(lock) {
            val removed = activeJobs.remove(easyHandle)
            if (removed != null) {
                if (cause != null) removed.completeExceptionally(cause)
                else removed.cancel()
            }

            if (easyHandle.isCleanedUp()) {
                onRemoved?.invoke()
                return
            }

            if (state.load() == State.CLOSED) {
                easyHandle.cleanUp()
                onRemoved?.invoke()
                return
            }
            pendingEventLoopActions.add(EventLoopAction.Remove(easyHandle, onRemoved))
        }
        wakeup()
    }

    /**
     * Unpause an easy handle that was paused via CURL_WRITEFUNC_PAUSE or CURL_READFUNC_PAUSE.
     * Thread-safe - can be called from any thread.
     *
     * @param easyHandle The easy handle to unpause
     */
    fun unpause(easyHandle: EasyHandle) {
        synchronized(lock) {
            if (state.load() == State.CLOSED
                || !activeJobs.containsKey(easyHandle)
                || easyHandle.isCleanedUp()
            ) {
                return
            }
            pendingEventLoopActions.add(EventLoopAction.Unpause(easyHandle))
        }
        wakeup()
    }

    /**
     * Wake up the event loop to process pending operations.
     */
    private fun wakeup() {
        suspendCondition.resume()
        curl_multi_wakeup(multiHandle)
    }

    /**
     * Main event loop - runs on the worker thread.
     */
    private suspend fun eventLoop() {
        memScoped {
            val runningHandles = alloc<IntVar>()

            var performError: IOException? = null
            while (state.load() == State.RUNNING) {
                // Process pending operations
                processPendingOperations()

                // Perform transfers
                val performResult = curl_multi_perform(multiHandle, runningHandles.ptr)
                if (performResult != CURLM_OK) {
                    performError =
                        IOException("curl_multi_perform failure ($performResult): ${curl_multi_strerror(performResult)?.toKString()}")
                    break // shutdown
                }

                // Check for completed transfers
                processCompletedTransfers()

                if (state.load() == State.RUNNING) {
                    // Wait for activity with timeout
                    if (runningHandles.value > 0) {
                        curl_multi_poll(multiHandle, null, 0u, POLL_TIMEOUT_MS, null)
                    } else {
                        suspendCondition.await()
                    }
                }
            }

            // Cleanup remaining jobs on shutdown
            finishEventLoop(performError)
        }
    }

    /**
     * Process pending add/cancel/unpause operations.
     */
    private fun processPendingOperations() {
        // Drain under the lock, execute OUTSIDE it: unpause() is curl_easy_pause(CONT), which
        // synchronously reenters the handle's write callback — and those callbacks take the
        // per-transfer buffer locks whose holders call back into this class (taking `lock`).
        // Executing curl calls while holding `lock` is a lock-order inversion that deadlocks the
        // event loop under backpressure. The curl calls themselves need no lock: the multi handle
        // and EasyHandle.cleanUp are only ever driven from this thread while RUNNING.
        val actions = synchronized(lock) {
            val drained = pendingEventLoopActions.toList()
            pendingEventLoopActions.clear()
            suspendCondition.pause()
            drained
        }
        actions.forEach { action ->
            when (action) {
                is EventLoopAction.Add -> {
                    val result = action.easyHandle.addTo(multiHandle)
                    if (result != CURLM_OK) {
                        val completion = synchronized(lock) { activeJobs[action.easyHandle] }
                        completion?.completeExceptionally(
                            IOException("Failed to add handle ($result): ${curl_multi_strerror(result)?.toKString()}")
                        )
                    }
                }

                is EventLoopAction.Remove -> {
                    action.easyHandle.cleanUp()
                    action.onRemoved?.invoke()
                }

                is EventLoopAction.Unpause -> {
                    val active = synchronized(lock) { activeJobs.containsKey(action.easyHandle) }
                    if (active && !action.easyHandle.isCleanedUp()) {
                        action.easyHandle.unpause()
                    }
                }
            }
        }
    }

    /**
     * Process completed transfers from curl_multi_info_read.
     */
    private fun processCompletedTransfers() {
        memScoped {
            val msgsInQueue = alloc<IntVar>()

            while (true) {
                val info = curl_multi_info_read(multiHandle, msgsInQueue.ptr) ?: break
                val msg = info.pointed

                if (msg.msg == CURLMSG.CURLMSG_DONE) {
                    val handle = msg.easy_handle ?: continue
                    val curlCode = msg.data.result
                    val easyHandle = try {
                        EasyHandle.from(handle)
                    } catch (e: Throwable) {
                        throw RuntimeException("Receive invalid easy handle pointer $handle", e)
                    }
                    activeJobs[easyHandle]?.complete(curlCode)
                }
            }
        }
    }

    /**
     * Cleanup remaining jobs when shutting down.
     */
    private fun finishEventLoop(performError: IOException?) {
        synchronized(lock) {
            check(state.exchange(State.CLOSED) != State.CLOSED)
            multiHandleFailure = performError

            pendingEventLoopActions.forEach { action ->
                when (action) {
                    is EventLoopAction.Add -> Unit // no op
                    is EventLoopAction.Remove -> {
                        action.easyHandle.cleanUp()
                        action.onRemoved?.invoke()
                    }

                    is EventLoopAction.Unpause -> {
                        if (activeJobs.containsKey(action.easyHandle)) {
                            action.easyHandle.unpause()
                        }
                    }
                }
            }
            pendingEventLoopActions.clear()
            activeJobs.values.forEach {
                if (performError != null) it.completeExceptionally(performError)
                else it.cancel()
            }
        }
    }

    fun release() {
        if (state.compareAndSet(State.RUNNING, State.CLOSING)) {
            wakeup() // Wake up the event loop to exit
            suspendCondition.release()
            synchronized(lock) {
                checkNotNull(startedJob).invokeOnCompletion {
                    check(state.load() == State.CLOSED)
                    curl_multi_cleanup(multiHandle)
                }
                startedJob = null
            }
        }
    }

    companion object {
        private const val POLL_TIMEOUT_MS = 30 * 1000
    }
}
