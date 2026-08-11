package io.ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bundle's "shutdown" contract at the engine level: crons stop, callbacks run, pipes drain. */
class EngineShutdownTest {

    private suspend fun DynamicEngine.drive(command: String): String {
        val pipe = openPipe(command)
        return try {
            val head = Buffer().writeUtf8("{}")
            pipe.sink.send(head, head.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    private suspend fun DynamicEngine.signalShutdown() {
        val pipe = openPipe("shutdown")
        try {
            pipe.sink.close()
            val buf = Buffer()
            while (pipe.source.receive(buf, 4096L) != -1L) { /* EOF = ack */ }
        } finally {
            pipe.source.close()
        }
    }

    @Test
    fun shutdown_stops_crons_runs_callbacks_and_the_engine_drains_to_idle() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            assertEquals("started", e.drive("test/lifecycle/start-cron")) // a live 60s interval
            e.signalShutdown()
            assertEquals(
                """{"shuttingDown":true,"cbRan":true}""",
                e.drive("test/lifecycle/state"), // host commands stay served while draining
            )
            e.awaitIdle() // the cleared cron's pipe closed — nothing suspends this
        } finally {
            e.close()
        }
    }
}
