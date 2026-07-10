package ymusic.dynamicengine

import kmpcommon.log.Logger
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException
import ymusic.quickjs.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP protocol's birth-site logging: the scraper tags a request with `HttpRequest.logContext`,
 *  it rides the head to [HttpProtocol], and a transport failure is logged there — where it's born,
 *  with the trail + the native cause — through the app's [Logger]. */
class HostLoggingTest {

    private class Capture : Logger {
        data class Entry(val level: Logger.Level, val tags: List<String>, val message: String, val error: Throwable?)

        val calls = mutableListOf<Entry>()
        override fun log(level: Logger.Level, tags: List<String>, message: String, error: Throwable?) {
            calls += Entry(level, tags, message, error)
        }
    }

    private suspend fun Pipe.drain() {
        val b = Buffer()
        while (source.receive(b, 4096L) != -1L) { /* to EOF */ }
    }

    @Test
    fun http_transport_failure_logs_at_its_birth_site_with_the_context() = runTest {
        val log = Capture()
        val engine = createTestEngine(FakeHttpClient { throw IOException("connect refused") }, 1, log)
        try {
            engine.load(
                """
                ymix.onAccept("probe", async (p) => {
                  try {
                    var res = await HttpRequest.get("https://youtube.com/watch")
                      .logContext(["download#a1b2", "js:fetchForLinkParser"])
                      .build()
                      .execute();
                    await res.text();
                  } catch (e) {}
                  p.sink.close();
                });
                """.trimIndent(),
            )
            engine.openPipe("probe").drain()

            // The failure is logged once, where it's born, with the trail + this protocol's hop.
            assertEquals(1, log.calls.size, "the transport failure must be logged once at its birth site")
            val entry = log.calls.single()
            assertEquals(Logger.Level.WARN, entry.level)
            assertEquals(listOf("download#a1b2", "js:fetchForLinkParser", "http"), entry.tags)
            assertTrue(entry.message.startsWith("request failed:"), "message was: ${entry.message}")
            assertTrue(entry.message.contains("connect refused"), "message was: ${entry.message}")
            assertTrue(entry.error is IOException, "the native cause is logged for fidelity")
        } finally {
            engine.close()
        }
    }

    @Test
    fun no_context_degrades_to_just_the_protocol_hop() = runTest {
        val log = Capture()
        val engine = createTestEngine(FakeHttpClient { throw IOException("dns") }, 1, log)
        try {
            engine.load(
                """
                ymix.onAccept("probe", async (p) => {
                  try {
                    await HttpRequest.get("https://x/").build().execute();
                  } catch (e) {}
                  p.sink.close();
                });
                """.trimIndent(),
            )
            engine.openPipe("probe").drain()
            assertEquals(listOf("http"), log.calls.single().tags)
        } finally {
            engine.close()
        }
    }

    @Test
    fun no_logger_is_a_safe_no_op() = runTest {
        val engine = createTestEngine(FakeHttpClient { throw IOException("boom") }, 1)
        try {
            engine.load(
                """
                ymix.onAccept("probe", async (p) => {
                  try { await HttpRequest.get("https://x/").build().execute(); } catch (e) {}
                  p.sink.close();
                });
                """.trimIndent(),
            )
            engine.openPipe("probe").drain() // must not NPE with a null logger
        } finally {
            engine.close()
        }
    }
}
