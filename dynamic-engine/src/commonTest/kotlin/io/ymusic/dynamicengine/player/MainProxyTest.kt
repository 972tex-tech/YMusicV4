package io.ymusic.dynamicengine.player

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngine
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.createTestEngine
import io.ymusic.dynamicengine.decodeEngineError
import io.ymusic.dynamicengine.internal.ENGINE_TEST_JS
import io.ymusic.dynamicengine.internal.installMainProxy
import io.ymusic.dynamicengine.testEngineDispatcher
import io.ymusic.quickjs.PipeResetException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MainProxyTest {

    private suspend fun DynamicEngine.drive(command: String, head: String = "{}"): String {
        val pipe = openPipe(command)
        return try {
            val h = Buffer().writeUtf8(head)
            pipe.sink.send(h, h.size)
            pipe.sink.close()
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.source.close()
        }
    }

    @Test
    fun player_callMain_reaches_the_main_engine() = runTest {
        val main = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        val player = withContext(testEngineDispatcher) {
            DynamicEngine.createEngine(
                FakeHttpClient(CannedResponse.ofText("")),
                1,
                testEngineDispatcher,
                engineJs = ENGINE_TEST_JS,
            ) { parked ->
                installMainProxy(parked) { cmd -> main.openPipe(cmd) }
            }.also { it.healthCheck() }
        }
        try {
            assertEquals("got:from-player", player.drive("test/main/echo", """{"x":"from-player"}"""))
        } finally {
            player.close()
            main.close()
        }
    }

    @Test
    fun missing_main_engine_is_E20078() = runTest {
        val player = withContext(testEngineDispatcher) {
            DynamicEngine.createEngine(
                FakeHttpClient(CannedResponse.ofText("")),
                1,
                testEngineDispatcher,
                engineJs = ENGINE_TEST_JS,
            ) { parked ->
                installMainProxy(parked) { throw NoMainEngine() }
            }.also { it.healthCheck() }
        }
        try {
            val e = assertFailsWith<PipeResetException> {
                player.drive("test/main/echo", """{"x":"x"}""")
            }
            assertEquals("E20078", decodeEngineError(e.reason, e).code)
        } finally {
            player.close()
        }
    }

    @Test
    fun unknown_main_command_is_E20080() = runTest {
        val main = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        val player = withContext(testEngineDispatcher) {
            DynamicEngine.createEngine(
                FakeHttpClient(CannedResponse.ofText("")),
                1,
                testEngineDispatcher,
                engineJs = ENGINE_TEST_JS,
            ) { parked ->
                installMainProxy(parked) { cmd -> main.openPipe(cmd) }
            }.also { it.healthCheck() }
        }
        try {
            val r = player.drive("test/main/missing")
            assertTrue(r.startsWith("ERR:E20080:"), r)
        } finally {
            player.close()
            main.close()
        }
    }

    @Test
    fun main_js_reset_crosses_with_its_code() = runTest {
        val main = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        val player = withContext(testEngineDispatcher) {
            DynamicEngine.createEngine(
                FakeHttpClient(CannedResponse.ofText("")),
                1,
                testEngineDispatcher,
                engineJs = ENGINE_TEST_JS,
            ) { parked ->
                installMainProxy(parked) { cmd -> main.openPipe(cmd) }
            }.also { it.healthCheck() }
        }
        try {
            val r = player.drive("test/main/boom")
            assertTrue(r.startsWith("ERR:parse:"), r)
        } finally {
            player.close()
            main.close()
        }
    }
}
