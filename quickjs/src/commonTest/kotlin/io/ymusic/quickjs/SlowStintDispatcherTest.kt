package io.ymusic.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The watchdog measures STINTS (one continuous stretch holding the engine thread), never command
 * wall time: long suspending work whose individual stints are short must stay silent — a command's
 * duration includes suspensions during which the thread serves everyone else.
 */
class SlowStintDispatcherTest {

    @Test
    fun one_long_stint_fires_but_long_suspending_work_does_not() = runTest {
        val hit = CompletableDeferred<Long>()
        val dispatcher = QuickJsRuntime.newDispatcher(slowStintMs = 50L, onSlowStint = { ms, _, _ -> hit.complete(ms) })
        try {
            // 250ms of wall time as many short stints: real delays (DefaultDelay — the wrapper
            // implements no Delay), each resumption its own dispatch. Must not fire.
            withContext(dispatcher) {
                repeat(10) { delay(25) }
            }
            assertFalse(hit.isCompleted, "suspending work must not trip the stint watchdog")

            // One 80ms busy stint: must fire. The callback lands in the dispatch wrapper's finally,
            // AFTER the outer coroutine has already resumed — await it, don't assert immediately.
            withContext(dispatcher) {
                val mark = TimeSource.Monotonic.markNow()
                @Suppress("ControlFlowWithEmptyBody")
                while (mark.elapsedNow() < 80.milliseconds) {
                }
            }
            val ms = withContext(Dispatchers.Default) { withTimeout(5_000) { hit.await() } }
            assertTrue(ms >= 50L, "stint of ${ms}ms should be at/over the 50ms budget")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun a_task_queued_behind_a_busy_thread_trips_the_wait_watchdog() = runTest {
        val wait = CompletableDeferred<Long>()
        val dispatcher = QuickJsRuntime.newDispatcher(slowWaitMs = 50L, onSlowWait = { wait.complete(it) })
        try {
            withContext(Dispatchers.Default) {
                val busy = launch(dispatcher) {
                    val mark = TimeSource.Monotonic.markNow()
                    @Suppress("ControlFlowWithEmptyBody")
                    while (mark.elapsedNow() < 80.milliseconds) {
                    }
                }
                val victim = launch(dispatcher) { /* just needs the thread */ }
                val ms = withTimeout(5_000) { wait.await() }
                assertTrue(ms >= 50L, "queued task waited ${ms}ms; expected 50ms+")
                busy.join()
                victim.join()
            }
        } finally {
            dispatcher.close()
        }
    }
}
