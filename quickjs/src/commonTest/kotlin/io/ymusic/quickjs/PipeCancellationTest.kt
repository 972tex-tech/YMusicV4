package io.ymusic.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Any abnormal exit from send/receive (cancellation included) closes that side of the pipe — ONE
 * predictable state, never a half-transferred stream. Without this rule, a send cancelled while
 * parked at the 64 KB cap left the peer a truncated stream with NO error signal (measured: 65536
 * of 204800 bytes, error=none), spliced the next message into the middle of it, and could hang the
 * next send forever. JS halves live in the transport fixtures (test-bundle `test/…` commands).
 */
class PipeCancellationTest {

    /** Serve the fixtures' "report" channel: one message to EOF. */
    private fun QuickJsRuntime.serveReport(): CompletableDeferred<String> {
        val report = CompletableDeferred<String>()
        onAccept("report") { p ->
            val b = Buffer()
            while (p.source.receive(b, 8192L) != -1L) { /* to EOF */ }
            report.complete(b.readUtf8())
        }
        return report
    }

    @Test
    fun cancelled_mid_stream_send_resets_the_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val report = rt.serveReport()
            // The fixture parks its reads on the "test-gate" pipe until we poke it.
            val gateOpened = CompletableDeferred<Pipe>()
            rt.onAccept("test-gate") { p -> gateOpened.complete(p); awaitCancellation() }

            val pipe = rt.openPipe("test/gated-drain")
            val payload = Buffer()
            repeat(200 * 1024) { payload.writeByte(it and 0xFF) }
            val sender = launch { pipe.sink.send(payload, payload.size) }
            withContext(Dispatchers.Default) { delay(300) } // real time: park at the 64 KB cap
            sender.cancelAndJoin()

            // The abort moved bytes -> the pipe RESET: the peer's read throws the reason (its
            // truncated buffered prefix is discarded, not served as a fake clean stream)...
            gateOpened.await().let { gate ->
                val go = Buffer().writeByte(1)
                gate.sink.send(go, go.size)
            }
            val (bytes, err) = report.await().split("|", limit = 2)
            assertEquals("0", bytes)
            assertTrue(err.startsWith("send aborted mid-stream:"), "peer saw: $err")

            // ...and this side has declared itself done writing — no silent splicing.
            assertFailsWith<IllegalStateException> { pipe.sink.send(Buffer().writeUtf8("SENTINEL"), 8L) }
        } finally {
            rt.close()
        }
    }

    /** `finally { rt.close() }` on an already-cancelled job is the standard cleanup pattern — the
     *  teardown must still run (fail parked calls, destroy the engine), not get skipped by the
     *  caller's own cancellation. Observable: the parked receive below resumes with the runtime-
     *  closed failure instead of hanging forever. */
    @Test
    fun close_completes_from_an_already_cancelled_caller() = runTest {
        val rt = QuickJsRuntime.create()
        val pipe = rt.openPipe("test/keep")
        val parked = async(Dispatchers.Default) { runCatching { pipe.source.receive(Buffer(), 4L) } }
        withContext(Dispatchers.Default) { delay(200) } // real time: let the receive park

        val closer = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { rt.close() } // cleanup path: caller is cancelled here
            }
        }
        withContext(Dispatchers.Default) { delay(100) }
        closer.cancelAndJoin()

        val result = parked.await()
        assertTrue(result.isFailure, "teardown must fail the parked receive, got $result")
    }

    /** A peer that closeRead()s mid-stream (from the data path, inside engine.write's microtask
     *  pump) must fail the in-flight send with EPIPE promptly on every interleave — including the
     *  narrow one where the flag lands between a partial accept and the writer's park (that park
     *  now rechecks peerReadClosed; the window needs a pump/cap coincidence that segment-sized
     *  writes make impractical to pin down deterministically, so this covers the family). */
    @Test
    fun peer_closeRead_during_the_write_pump_fails_the_send_instead_of_hanging() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/read-once-close")
            val payload = Buffer()
            repeat(200 * 1024) { payload.writeByte(it and 0xFF) }
            val result = withContext(Dispatchers.Default) {
                withTimeout(10_000) { runCatching { pipe.sink.send(payload, payload.size) } }
            }
            assertTrue(result.exceptionOrNull() is BrokenPipeException, "expected EPIPE, got $result")
        } finally {
            rt.close()
        }
    }

    /** `finally { pipe.sink.close() }` on an already-cancelled job must still half-close the pipe:
     *  the JS peer's read must see the END/RESET instead of waiting forever on a pipe whose
     *  Kotlin side silently skipped its own cleanup. */
    @Test
    fun pipe_close_still_runs_when_the_caller_is_already_cancelled() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val report = rt.serveReport()
            val pipe = rt.openPipe("test/recv-report")
            val worker = launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    pipe.sink.close("caller cancelled") // job is cancelled HERE — must still RESET
                }
            }
            withContext(Dispatchers.Default) { delay(200) } // real time: let JS park its receive
            worker.cancelAndJoin()

            assertEquals("error:caller cancelled", report.await())
        } finally {
            rt.close()
        }
    }

    /** withTimeout inside a handler throws a CancellationException whose job is NOT cancelled —
     *  a failure wearing cancellation's type. It must RESET the pipe like any other handler
     *  failure; rethrowing it just kills the handler coroutine and leaves the JS peer parked
     *  forever on a pipe nobody owns. */
    @Test
    fun handler_timeout_resets_the_pipe_instead_of_leaking_it() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            rt.onAccept("slow") { _ ->
                withTimeout(50) { awaitCancellation() } // handler dies via TimeoutCancellationException
            }
            val pipe = rt.openPipe("test/probe-slow")
            pipe.sink.close() // empty head: the fixture reads to EOF then probes "slow"
            val out = Buffer()
            val outcome = withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (pipe.source.receive(out, 64L) != -1L) { /* to EOF */ }
                    out.readUtf8()
                }
            }
            assertTrue(outcome.startsWith("error:"), "peer must see the RESET, got: $outcome")
        } finally {
            rt.close()
        }
    }

    @Test
    fun cancelled_parked_receive_closes_the_read_side() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/keep")
            val parked = launch { pipe.source.receive(Buffer(), 16L) }
            withContext(Dispatchers.Default) { delay(200) } // real time: let it park
            parked.cancelAndJoin()

            // The abort closed our read end: this side's next receive is a misuse...
            assertFailsWith<IllegalStateException> { pipe.source.receive(Buffer(), 16L) }
            // ...and the peer's next send fails with EPIPE instead of writing into the void.
            val poke = rt.openPipe("test/poke-held")
            poke.sink.close() // empty head
            val out = Buffer()
            while (poke.source.receive(out, 64L) != -1L) { /* to EOF */ }
            poke.source.close()
            assertTrue(Regex("""EPIPE: broken pipe \[#\d+ test/keep]""").matches(out.readUtf8()))
        } finally {
            rt.close()
        }
    }
}
