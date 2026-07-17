package ymusic.imageloader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LifoGateTest {

    @Test
    fun newestWaiterIsServedFirst() = runTest {
        val gate = LifoGate(1)
        val order = mutableListOf<String>()
        val holdFirst = CompletableDeferred<Unit>()

        val first = launch { gate.withPermit { holdFirst.await() } }
        testScheduler.runCurrent() // first holds the only permit

        val a = launch { gate.withPermit { order.add("a") } }
        testScheduler.runCurrent()
        val b = launch { gate.withPermit { order.add("b") } }
        testScheduler.runCurrent()
        val c = launch { gate.withPermit { order.add("c") } }
        testScheduler.runCurrent()

        holdFirst.complete(Unit)
        first.join()
        a.join()
        b.join()
        c.join()
        assertEquals(listOf("c", "b", "a"), order, "waiters must run newest-first")
    }

    @Test
    fun cancelledWaiterLeavesTheStack() = runTest {
        val gate = LifoGate(1)
        val order = mutableListOf<String>()
        val holdFirst = CompletableDeferred<Unit>()

        val first = launch { gate.withPermit { holdFirst.await() } }
        testScheduler.runCurrent()

        val a = launch { gate.withPermit { order.add("a") } }
        testScheduler.runCurrent()
        val b = launch { gate.withPermit { order.add("b") } }
        testScheduler.runCurrent()

        b.cancelAndJoin() // the newest waiter scrolls away
        holdFirst.complete(Unit)
        first.join()
        a.join()
        assertEquals(listOf("a"), order, "a cancelled waiter must never run nor block others")
    }

    @Test
    fun permitsRunConcurrently() = runTest {
        val gate = LifoGate(2)
        var running = 0
        var maxRunning = 0
        val release = CompletableDeferred<Unit>()
        val jobs = List(4) {
            launch {
                gate.withPermit {
                    running++
                    maxRunning = maxOf(maxRunning, running)
                    release.await()
                    running--
                }
            }
        }
        testScheduler.runCurrent()
        assertEquals(2, maxRunning, "exactly `parallelism` loads may run at once")
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(2, maxRunning)
    }
}
