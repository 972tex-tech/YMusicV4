package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS_VERSION
import io.ymusic.dynamicengine.yt.jsonCommand
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException
import projectcommon.log.trailMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The HTTP protocol's birth-site logging: the scraper attaches a crumb-trail logger, it rides
 *  the head to [HttpProtocol], and a transport failure is logged there — where it's born, with the
 *  trail + the native cause — through the app's [Logger]. Driven via the `test/http-log` fixture
 *  (engine-js/src/test/fixtures/logging.ts). */
class HostLoggingTest {

    private class Capture private constructor(
        crumbs: List<Crumb>,
        val calls: MutableList<Entry>,
    ) : CrumbLogger(crumbs) {
        constructor() : this(emptyList(), mutableListOf())

        data class Entry(
            val level: Logger.Level,
            val crumbs: List<Crumb>,
            val message: String,
            val error: Throwable?,
        ) {
            val tags: List<String> get() = crumbs.map { it.tag }
        }

        override fun fork(crumbs: List<Crumb>): CrumbLogger = Capture(crumbs, calls)

        override fun log(level: Logger.Level, message: String, error: Throwable?) {
            calls += Entry(level, crumbs, message, error)
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
            engine.probe("""{"url":"https://youtube.com/watch","ctx":["download","fetchForLinkParser"]}""")

            // The failure is logged once, where it's born, with the trail + this protocol's hop.
            // The bundle bakes its own <name>:<version> as the trail root (logger.ts VERSION_TAG).
            val entry = log.calls.single { it.message.startsWith("request failed:") }
            assertEquals(Logger.Level.WARN, entry.level)
            assertEquals(listOf("engine:$ENGINE_TEST_JS_VERSION", "js:download", "js:fetchForLinkParser", "http"), entry.tags)
            assertEquals(mapOf<String, Any>("url" to "https://youtube.com/watch"), entry.crumbs.last().prop)
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
            assertEquals(
                listOf("engine:$ENGINE_TEST_JS_VERSION", "http"),
                log.calls.single { it.message.startsWith("request failed:") }.tags,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun parked_caller_logger_joins_the_js_trail() = runTest {
        val log = Capture()
        val engine = createTestEngine(FakeHttpClient { throw IOException("connect refused") }, 1, log)
        try {
            val caller = log.fork("Discover").fork("task", mapOf("id" to "a1"))
            engine.jsonCommand(
                "test/http-log",
                { w -> w.name("url").value("https://youtube.com/watch") },
                caller,
            ) { it.skipValue() }

            // The birth-site WARN carries the Kotlin caller's trail (via the parked key), the JS
            // side's forks WITH their props (by value), and the protocol's own hop.
            val entry = log.calls.single { it.message.startsWith("request failed:") }
            assertEquals(listOf("Discover", "task", "engine:$ENGINE_TEST_JS_VERSION", "js:fetch", "js:sub", "http"), entry.tags)
            assertEquals(mapOf<String, Any>("id" to "a1"), entry.crumbs[1].prop)
            assertEquals(mapOf<String, Any>("n" to 1L), entry.crumbs[4].prop)
            assertTrue(entry.error is IOException)
        } finally {
            engine.close()
        }
    }

    @Test
    fun a_throw_out_of_jsonCommand_carries_the_deepest_trail() = runTest {
        val log = Capture()
        val engine = createTestEngine(FakeHttpClient { throw IOException("unused") }, 1, log)
        try {
            val caller = log.fork("Discover").fork("extractBoom", mapOf("url" to "/x"))
            val e = assertFailsWith<EngineError> {
                engine.jsonCommand("test/boom", { }, caller) { it.skipValue() }
            }
            // The trail would have died with the unwind — jsonCommand stamped it in flight.
            assertEquals(listOf("Discover", "extractBoom"), e.trailMark()?.map { it.tag })
            assertEquals(mapOf<String, Any>("url" to "/x"), e.trailMark()?.last()?.prop)
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
