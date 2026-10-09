package io.ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.Buffer
import projectcommon.error.CodedError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The HOST half of the error contract: Kotlin serves commands with [commandHandler], and the
 * bundle's `callHost` opens them and decodes the RESET (`hostError`) back into a coded error. Driven
 * end to end through the test-bundle fixtures (engine-js/src/test/fixtures/protocol.ts, the
 * `test/callhost/…` commands) — each fixture opens a `hostcmd/…` this test registers and reports the outcome.
 */
class HostCommandTest {

    private class Coded(override val code: String, message: String, override val userMessage: String? = null) :
        Exception(message), CodedError

    /** Drive a `test/callhost/…` fixture (send an empty head, read the reported outcome to EOF). */
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

    @Test
    fun ends_on_success_and_callhost_reads_the_result() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.onAccept(
                "hostcmd/echo",
                e.commandHandler { src, out ->
                    val buf = Buffer()
                    while (src.receive(buf, 4096L) != -1L) { /* read the request to EOF */ }
                    out.send(buf, buf.size) // commandHandler ENDs the sink after we return
                },
            )
            assertEquals("OK:ping", e.drive("test/callhost/echo"))
        } finally {
            e.close()
        }
    }

    @Test
    fun a_coded_throw_crosses_with_its_code() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.onAccept("hostcmd/boom", e.commandHandler { _, _ -> throw Coded("network", "boom") })
            assertEquals("ERR:network:boom", e.drive("test/callhost/boom"))
        } finally {
            e.close()
        }
    }

    @Test
    fun a_site_code_in_the_message_is_preserved() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.onAccept("hostcmd/boom", e.commandHandler { _, _ -> error("E24242 shelf renderer shape") })
            assertEquals("ERR:E24242:E24242 shelf renderer shape", e.drive("test/callhost/boom"))
        } finally {
            e.close()
        }
    }

    @Test
    fun an_uncoded_throw_defaults_to_E20002() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            e.onAccept("hostcmd/boom", e.commandHandler { _, _ -> throw RuntimeException("plain fail") })
            assertEquals("ERR:E20002:plain fail", e.drive("test/callhost/boom"))
        } finally {
            e.close()
        }
    }
}
