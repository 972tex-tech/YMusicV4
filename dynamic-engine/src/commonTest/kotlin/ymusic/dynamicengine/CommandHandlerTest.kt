package ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.Buffer
import ymusic.quickjs.PipeResetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The JS half of the error contract, composable: `commandHandler(handler)` wraps a call handler
 *  (reads its own head off `src`, writes to `out`) so it can be handed to `ymix.onAccept` — ENDing
 *  on success, classifying + RESETting on a throw. Drives it over the raw pipe (no `DynamicEngine.call`
 *  — that's gone) and decodes the RESET. */
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
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.load(
                """
                ymix.onAccept("echo", commandHandler(async (src, out) => {
                  const a = await src.readJsonObject();
                  await out.writeUtf8("got:" + a.x);
                }));
                """.trimIndent(),
            )
            val (text, err) = e.run("echo", """{"x":42}""")
            assertEquals(null, err)
            assertEquals("got:42", text)
        } finally {
            e.close()
        }
    }

    @Test
    fun command_handler_failure_resets_with_a_classified_error() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.load(
                """
                ymix.onAccept("boom", commandHandler(async (src, out) => {
                  await src.readJsonObject();
                  throw JsEngineError.parse("bad shape");
                }));
                """.trimIndent(),
            )
            val (_, err) = e.run("boom", "{}")
            assertIs<EngineError.Parse>(err)
            assertEquals("bad shape", err.message)
            assertEquals("JsEngineError", err.jsName)
        } finally {
            e.close()
        }
    }
}
