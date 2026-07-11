package ymusic.dynamicengine

import kmpcommon.io.BufferedAsyncSource
import kmphttp.json.JsonReader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.IOException
import ymusic.quickjs.PipeResetException
import ymusic.quickjs.asAsyncSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Smoke over the PRODUCTION bundle (the Closure-ADVANCED-mangled `ENGINE_JS` default): driven
 *  purely via pipes and the built-in diagnostics commands — no JS symbol is referenced, which is
 *  exactly the production contract. Everything else tests the TEST bundle; this is what proves the
 *  mangled artifact itself boots and serves. */
class ProdBundleSmokeTest {

    @Test
    fun ping_echoes_through_the_mangled_bundle() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("")), 1)
        try {
            val pipe = e.openPipe("ping")
            val msg = Buffer().writeUtf8("hello, mangled world")
            pipe.sink.send(msg, msg.size)
            pipe.sink.close() // END: the fixture reads to EOF
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            assertEquals("hello, mangled world", out.readUtf8())
            pipe.source.close()
        } finally {
            e.close()
        }
    }

    @Test
    fun http_fetch_streams_head_and_body() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("payload bytes", status = 200)), 1)
        try {
            val pipe = e.openPipe("http/fetch")
            val head = Buffer().writeUtf8("""{"url":"https://example.com/x"}""")
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val src = BufferedAsyncSource(pipe.source.asAsyncSource())
            val rh = JsonReader(src).readObject()
            assertEquals(200, rh["status"]?.jsonPrimitive?.int)
            val body = Buffer()
            while (src.read(body, 4096L) != -1L) { /* to EOF */ }
            assertEquals("payload bytes", body.readUtf8())
            pipe.source.close()
        } finally {
            e.close()
        }
    }

    /** The error contract survives mangling: a transport failure inside the bundle crosses as the
     *  classified `{code:"network"}` RESET and decodes to the typed error. */
    @Test
    fun http_fetch_failure_resets_with_the_network_code() = runTest {
        val e = DynamicEngine.create(FakeHttpClient { throw IOException("connect refused") }, 1)
        try {
            val pipe = e.openPipe("http/fetch")
            val head = Buffer().writeUtf8("""{"url":"https://example.com/x"}""")
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val err = try {
                val out = Buffer()
                while (pipe.source.receive(out, 4096L) != -1L) out.clear()
                null
            } catch (r: PipeResetException) {
                decodeEngineError(r.reason, r)
            }
            assertIs<EngineError.Network>(err)
            pipe.source.close()
        } finally {
            e.close()
        }
    }
}
