package ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.Buffer
import ymusic.dynamicengine.internal.ENGINE_TEST_JS
import kotlin.test.Test
import kotlin.test.assertEquals

/** compile() + create(bytecode): the C-installed versions and the HTTP protocol work when the
 *  bundle runs from stripped bytecode instead of source. */
class BytecodeEngineTest {

    private suspend fun DynamicEngine.scenario(name: String, args: String = "{}"): String {
        val pipe = openPipe("test/http/$name")
        return try {
            val b = Buffer().writeUtf8(args)
            pipe.sink.send(b, b.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    @Test
    fun versions_reach_the_bytecode_bundle() = runTest {
        val bc = DynamicEngine.compile(ENGINE_TEST_JS)
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("")), appVersion = 42, engineBytecode = bc)
        try {
            assertEquals("app=42", e.scenario("versions"))
        } finally {
            e.close()
        }
    }

    @Test
    fun http_protocol_works_from_bytecode() = runTest {
        val bc = DynamicEngine.compile(ENGINE_TEST_JS)
        val client = FakeHttpClient(CannedResponse.ofText("Hello, world!"))
        val e = DynamicEngine.create(client, appVersion = 1, engineBytecode = bc)
        try {
            assertEquals("Hello, world!", e.scenario("get-text"))
        } finally {
            e.close()
        }
    }
}
