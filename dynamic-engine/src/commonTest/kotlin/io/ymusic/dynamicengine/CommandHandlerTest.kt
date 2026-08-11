package io.ymusic.dynamicengine

import io.ymusic.quickjs.PipeResetException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The JS half of the error contract, driven purely via pipes against the test-bundle fixtures
 *  (engine-js/src/test/fixtures/protocol.ts): `commandHandler` ENDs on success, classifies +
 *  RESETs on a throw, and the Kotlin side decodes the RESET into a typed [EngineError]. */
class CommandHandlerTest {

    /** Send [head], drain the result to text; a RESET is decoded to a typed [EngineError]. */
    private suspend fun DynamicEngine.run(command: String, head: String): Pair<String, EngineError?> {
        val pipe = openPipe(command)
        return try {
            val b = Buffer().writeUtf8(head)
            pipe.sink.send(b, b.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8() to null
        } catch (e: PipeResetException) {
            "" to decodeEngineError(e.reason, e)
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    @Test
    fun command_handler_round_trips_a_result() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            val (text, err) = e.run("test/echo", """{"x":42}""")
            assertEquals(null, err)
            assertEquals("got:42", text)
        } finally {
            e.close()
        }
    }

    @Test
    fun command_handler_failure_resets_with_a_classified_error() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            val (_, err) = e.run("test/parse-boom", "{}")
            assertIs<EngineError.Parse>(err)
            assertEquals("bad shape", err.message)
            assertEquals("JsEngineError", err.jsName)
        } finally {
            e.close()
        }
    }
}
