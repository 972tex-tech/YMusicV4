package io.ymusic.quickjs

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwaitIdleTest {

    @Test
    fun returns_immediately_when_no_pipe_is_open() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            rt.awaitIdle()
        } finally {
            rt.close()
        }
    }

    @Test
    fun completes_when_the_last_pipe_closes() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/echo")
            val idle = async { rt.awaitIdle() }
            yield()
            assertFalse(idle.isCompleted, "must wait while a pipe is open")
            val head = Buffer().writeUtf8("hi")
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            assertEquals("hi", out.readUtf8())
            pipe.source.close()
            idle.await() // both ends closed on both sides -> reaped -> idle
        } finally {
            rt.close()
        }
    }

    @Test
    fun a_bounded_wait_times_out_while_a_pipe_stays_open() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/keep") // the fixture holds the pipe open
            assertNull(withTimeoutOrNull(200) { rt.awaitIdle() })
            pipe.sink.close()
            pipe.source.close()
        } finally {
            rt.close()
        }
    }

    @Test
    fun close_completes_a_parked_waiter() = runTest {
        val rt = QuickJsRuntime.create()
        rt.openPipe("test/keep")
        val idle = async { rt.awaitIdle() }
        yield()
        assertFalse(idle.isCompleted)
        rt.close()
        idle.await() // completes (no pipes is factually true), never hangs
        assertTrue(true)
    }
}
