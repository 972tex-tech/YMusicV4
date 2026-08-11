package io.ymusic.quickjs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runtime close() lifecycle: exactly one teardown ever reaches the native engine. A second
 * close(), or a pipe op that races teardown (the everyday `finally { pipe.sink.close() }`), must not
 * call into an engine whose handle `engine.destroy()` already freed — that's a native
 * use-after-free, not an exception. Held pipes come from the `test/keep` fixture.
 */
class RuntimeCloseTest {

    @Test
    fun close_is_idempotent() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load("1 + 1")
        rt.close()
        rt.close() // second close: no double-destroy, no dispatch to the closed engine thread
    }

    @Test
    fun pipe_ops_after_runtime_close_fail_fast_without_touching_the_engine() = runTest {
        val rt = QuickJsRuntime.create()
        val pipe = rt.openPipe("test/keep")
        rt.close()

        // Closes are cleanup calls — safe no-ops (teardown already closed every pipe)...
        pipe.sink.close()
        pipe.source.close()
        // ...while data ops are failures the caller must see.
        assertFailsWith<QuickJsException> { pipe.sink.send(Buffer().writeUtf8("late"), 4L) }
        assertFailsWith<QuickJsException> { pipe.source.receive(Buffer(), 4L) }
    }

    /** The finding's crash shape: a receive parked when close() tears down resumes with the
     *  teardown failure, and its `finally { pipe.sink.close() }` lands on the engine queue BEHIND
     *  `engine.destroy()`. The poisoned own-side flags must make it bail without an engine call. */
    @Test
    fun pipe_close_in_finally_racing_runtime_close_is_safe() = runTest {
        val rt = QuickJsRuntime.create()
        val pipe = rt.openPipe("test/keep")
        val worker = async(Dispatchers.Default) {
            runCatching {
                try {
                    pipe.source.receive(Buffer(), 4L)
                } finally {
                    pipe.sink.close()
                }
            }
        }
        withContext(Dispatchers.Default) { delay(200) } // real time: let the receive park
        rt.close()

        val result = worker.await()
        assertTrue(result.isFailure, "teardown must fail the parked receive, got $result")
    }

    /** openPipe()/load() had no close-guard at all, so after close() they dispatched to the closed
     *  engine thread and surfaced kotlinx's RejectedExecution as a CancellationException (or, when
     *  racing, called into the freed engine). They must fail-fast as QuickJsException like the data
     *  ops do. */
    @Test
    fun open_pipe_and_load_after_runtime_close_fail_fast() = runTest {
        val rt = QuickJsRuntime.create()
        rt.close()
        assertFailsWith<QuickJsException> { rt.openPipe("anything") }
        assertFailsWith<QuickJsException> { rt.load("1 + 1") }
    }

    /** The reaped-pipe UAF (audit K2): a pipe reaped by maybeReap (our END + peer END) has left the
     *  pipes map, so close()'s failPending never poisons it — yet the caller still holds it and runs
     *  `finally { source.close() }`. Racing that against runtime close() must stay off the freed
     *  engine (the engine-thread `closed` recheck, since the own-side flags can't guard here).
     *  Probabilistic — most valuable under the native/ASan run. */
    @Test
    fun reaped_pipe_close_racing_runtime_close_is_safe() = runTest {
        repeat(30) {
            val rt = QuickJsRuntime.create()
            val pipe = rt.openPipe("test/close-now")
            pipe.sink.close() // our END; the fixture's END makes writeEnded -> reaped
            withContext(Dispatchers.Default) { delay(10) } // let the JS END cross so the pipe reaps
            val worker = async(Dispatchers.Default) { runCatching { pipe.source.close() } }
            rt.close()
            assertTrue(worker.await().isSuccess, "reaped-pipe source.close() racing close() must not throw")
        }
    }
}
