package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.PLAYER_JS
import io.ymusic.quickjs.PipeResetException
import io.ymusic.quickjs.QuickJsException
import io.ymusic.quickjs.asAsyncSource
import kmpcommon.io.BufferedAsyncSource
import kmphttp.json.JsonReader
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import projectcommon.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Smoke over the PRODUCTION artifacts (Closure-ADVANCED-mangled `ENGINE_JS` scrape default and
 *  `PLAYER_JS` MSE player): driven purely via pipes — no JS symbol is referenced, which is
 *  exactly the production contract. Everything else tests the TEST bundle; this is what proves the
 *  mangled artifacts themselves boot and serve. */
class ProdBundleSmokeTest {

    private suspend fun createProdEngine(client: kmphttp.KmpHttpClient): DynamicEngine =
        withContext(testEngineDispatcher) { DynamicEngine.create(client, 1, testEngineDispatcher).also { it.healthCheck() } }

    @Test
    fun ping_echoes_through_the_mangled_bundle() = runTest {
        val e = createProdEngine(FakeHttpClient(CannedResponse.ofText("")))
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
        val e = createProdEngine(FakeHttpClient(CannedResponse.ofText("payload bytes", status = 200)))
        try {
            val pipe = e.openPipe("http/fetch")
            val head = Buffer().writeUtf8("""{"url":"https://example.com/x"}""")
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val src = BufferedAsyncSource(pipe.source.asAsyncSource())
            val rh = JsonReader(src).readObject()
            assertEquals(200, rh.intOrNull("status"))
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
        val e = createProdEngine(FakeHttpClient { throw IOException("connect refused") })
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

    @Test
    fun scrape_bundle_refuses_player_commands() = runTest {
        val e = createProdEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            val err = assertFailsWith<QuickJsException> { e.openPipe("player/init") }
            assertEquals("""open("player/init") refused: no JS handler""", err.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun player_bundle_boots_and_serves_player_init() = runTest {
        val e = withContext(testEngineDispatcher) {
            DynamicEngine.create(
                FakeHttpClient(CannedResponse.ofText("")),
                1,
                testEngineDispatcher,
                engineJs = PLAYER_JS,
            ).also { it.healthCheck() }
        }
        try {
            val pipe = e.openPipe("player/init")
            pipe.sink.close()
            pipe.source.close()
            val err = assertFailsWith<QuickJsException> { e.openPipe("ping") }
            assertEquals("""open("ping") refused: no JS handler""", err.message)
        } finally {
            e.close()
        }
    }
}
