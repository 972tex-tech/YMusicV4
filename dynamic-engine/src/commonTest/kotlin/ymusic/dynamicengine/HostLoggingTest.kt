package ymusic.dynamicengine

import kmpcommon.log.Logger
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP protocol's birth-site logging: the scraper tags a request with `logContext`, it rides
 *  the head to [HttpProtocol], and a transport failure is logged there — where it's born, with the
 *  trail + the native cause — through the app's [Logger]. Driven via the `test/http-log` fixture
 *  (engine-js/src/test/fixtures/logging.ts). */
class HostLoggingTest {

    private class Capture : Logger {
        data class Entry(val level: Logger.Level, val tags: List<String>, val message: String, val error: Throwable?)

        val calls = mutableListOf<Entry>()
        override fun log(level: Logger.Level, tags: List<String>, message: String, error: Throwable?) {
            calls += Entry(level, tags, message, error)
        }
    }

    /** Drive the fixture: send the JSON [head], drain to EOF (the request failure is swallowed
     *  JS-side — the assertion target is the captured log). */
    private suspend fun DynamicEngine.probe(head: String) {
        val pipe = openPipe("test/http-log")
        val b = Buffer().writeUtf8(head)
        pipe.sink.send(b, b.size)
        val out = Buffer()
        while (pipe.source.receive(out, 4096L) != -1L) out.clear()
        pipe.sink.close()
        pipe.source.close()
    }

    @Test
    fun http_transport_failure_logs_at_its_birth_site_with_the_context() = runTest {
        val log = Capture()
        val engine = createTestEngine(FakeHttpClient { throw IOException("connect refused") }, 1, log)
        try {
            engine.probe("""{"url":"https://youtube.com/watch","ctx":["download#a1b2","js:fetchForLinkParser"]}""")

            // The failure is logged once, where it's born, with the trail + this protocol's hop.
            val entry = log.calls.single { it.level == Logger.Level.WARN }
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
            engine.probe("""{"url":"https://x/"}""")
            assertEquals(listOf("http"), log.calls.single { it.level == Logger.Level.WARN }.tags)
        } finally {
            engine.close()
        }
    }

    @Test
    fun no_logger_is_a_safe_no_op() = runTest {
        val engine = createTestEngine(FakeHttpClient { throw IOException("boom") })
        try {
            engine.probe("""{"url":"https://x/"}""") // must not NPE with a null logger
        } finally {
            engine.close()
        }
    }
}
