package ymusic.dynamicengine

import kmpcommon.error.errorCode
import kmpcommon.io.BufferedAsyncSource
import kmphttp.json.JsonReader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import okio.IOException
import ymusic.quickjs.PipeSource
import ymusic.quickjs.asAsyncSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The error contract end to end: `serve()` classifies every JS failure into `{code, …}`, the RESET
 *  carries it across, and [DynamicEngine.call] rebuilds it as a typed [EngineError]. */
class EngineCallTest {

    private suspend fun readAll(src: PipeSource): String {
        val all = Buffer()
        while (src.receive(all, 4096L) != -1L) { /* to EOF */ }
        return all.readUtf8()
    }

    private val noArgs = buildJsonObject { }

    @Test
    fun call_round_trips_args_and_result() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load("""serve("echo", async (args, out) => { await out.writeObjectAsJson({ got: args.feed }); });""")
            val result = e.call("echo", buildJsonObject { put("feed", "1234") }) { src ->
                JsonReader(BufferedAsyncSource(src.asAsyncSource())).readObject()
            }
            assertEquals("1234", result["got"]?.jsonPrimitive?.content)
        } finally {
            e.close()
        }
    }

    @Test
    fun deliberate_parse_error_arrives_typed_with_stack() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load("""serve("x", async () => { throw ExtractError.parse("continuation token missing"); });""")
            val err = assertFailsWith<EngineError.Parse> { e.call("x", noArgs) { readAll(it) } }
            assertEquals("parse", err.code)
            assertEquals("continuation token missing", err.message)
            assertEquals("ExtractError", err.jsName)
            assertNotNull(err.jsStack, "the JS stack must cross for reports")
        } finally {
            e.close()
        }
    }

    @Test
    fun site_code_and_user_message_pass_through() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load(
                """serve("x", async () => {""" +
                    """ throw new ExtractError("E29999", "shelf renderer shape", { userMessage: "Bảo trì đến 15:00" });""" +
                    """ });""",
            )
            val err = assertFailsWith<EngineError.Technical> { e.call("x", noArgs) { readAll(it) } }
            assertEquals("E29999", err.code)
            assertEquals("Bảo trì đến 15:00", err.userMessage)
            assertEquals("E29999", err.errorCode()) // the CodedError walk finds it
        } finally {
            e.close()
        }
    }

    /** Remote JS newer than this app build may send categories we've never heard of — they land as
     *  Other with the code intact, never a crash. */
    @Test
    fun unknown_category_lands_as_other_with_the_code() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load("""serve("x", async () => { throw new ExtractError("region_blocked", "not here"); });""")
            val err = assertFailsWith<EngineError.Other> { e.call("x", noArgs) { readAll(it) } }
            assertEquals("region_blocked", err.code)
            assertEquals("not here", err.message)
        } finally {
            e.close()
        }
    }

    /** An uncaught TypeError is a script bug: classified E20001 with the JS constructor name. */
    @Test
    fun uncaught_type_error_is_e20001() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load("""serve("x", async () => { null.foo; });""")
            val err = assertFailsWith<EngineError.Technical> { e.call("x", noArgs) { readAll(it) } }
            assertEquals("E20001", err.code)
            assertEquals("TypeError", err.jsName)
        } finally {
            e.close()
        }
    }

    /** An uncaught HTTP failure inside the handler classifies as the network category — http.js
     *  tags the transport-stage reasons, no scraper effort needed. */
    @Test
    fun uncaught_http_failure_is_a_network_error() = runTest {
        val e = DynamicEngine.create(FakeHttpClient { throw IOException("dns down") })
        try {
            e.load(
                """serve("x", async () => {""" +
                    """ await HttpRequest.get("https://y").build().execute();""" +
                    """ });""",
            )
            val err = assertFailsWith<EngineError.Network> { e.call("x", noArgs) { readAll(it) } }
            assertEquals("network", err.code)
            assertEquals("request failed: dns down", err.message)
            assertEquals("NetworkError", err.jsName)
        } finally {
            e.close()
        }
    }

    @Test
    fun missing_handler_is_e20002() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            val err = assertFailsWith<EngineError.Technical> { e.call("nope", noArgs) { readAll(it) } }
            assertEquals("E20002", err.code)
        } finally {
            e.close()
        }
    }

    /** A raw ymix.onAccept handler (no serve()) still fails safely: the bootstrap's bare-message
     *  RESET decodes to the fallback code, message intact. */
    @Test
    fun unstructured_reset_falls_back_to_e20003() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load("""ymix.onAccept("raw", async (p) => { await p.source.readJsonObject(); throw new Error("boom"); });""")
            val err = assertFailsWith<EngineError.Technical> { e.call("raw", noArgs) { readAll(it) } }
            assertEquals("E20003", err.code)
            assertEquals("boom", err.message)
        } finally {
            e.close()
        }
    }

    /** A site code embedded in a plain-text reason (the "one literal serves grep and log"
     *  convention) is salvaged as THE code — deepest mint wins, the fallback doesn't shadow it. */
    @Test
    fun embedded_site_code_is_salvaged_from_a_bare_reason() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("ok")))
        try {
            e.load(
                """ymix.onAccept("raw", async (p) => {""" +
                    """ await p.source.readJsonObject(); throw new Error("E20011 invalid request: no url");""" +
                    """ });""",
            )
            val err = assertFailsWith<EngineError.Technical> { e.call("raw", noArgs) { readAll(it) } }
            assertEquals("E20011", err.code)
            assertTrue(err.message!!.contains("invalid request"))
        } finally {
            e.close()
        }
    }
}
