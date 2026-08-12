package io.ymusic.dynamicengine

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bundle's console lands in the app [Logger] with the engine-js scope tag. */
class ConsoleToLoggerTest {

    private class Capture private constructor(
        crumbs: List<Crumb>,
        val calls: MutableList<Entry>,
    ) : CrumbLogger(crumbs) {
        constructor() : this(emptyList(), mutableListOf())

        data class Entry(val level: Logger.Level, val tags: List<String>, val message: String, val error: Throwable?)

        override fun fork(crumbs: List<Crumb>): CrumbLogger = Capture(crumbs, calls)

        override fun log(level: Logger.Level, message: String, error: Throwable?) {
            calls += Entry(level, crumbs.map { it.tag }, message, error)
        }
    }

    @Test
    fun console_from_a_served_command_reaches_the_logger() = runTest {
        val log = Capture()
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")), 1, log)
        try {
            val pipe = e.openPipe("test/lifecycle/log")
            val head = Buffer().writeUtf8("{}")
            pipe.sink.send(head, head.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            assertEquals("logged", out.readUtf8())
            pipe.sink.close()
            pipe.source.close()

            val js = log.calls.filter { it.tags == listOf("engine-js") }
            val crumb = js.first { it.level == Logger.Level.DEBUG }
            assertEquals("""crumb 42 {"shape":true}""", crumb.message)
            val err = js.first { it.level == Logger.Level.ERROR }
            assertTrue(err.message.startsWith("boom Error: dead end"), err.message)
        } finally {
            e.close()
        }
    }
}
