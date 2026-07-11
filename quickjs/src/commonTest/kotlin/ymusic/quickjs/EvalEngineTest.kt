package ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The bare-create / one-shot [QuickJsRuntime.evalEngine] contract. */
class EvalEngineTest {

    @Test
    fun open_before_evalEngine_is_refused() = runTest {
        val rt = QuickJsRuntime.create(appVersion = 1)
        try {
            val e = assertFailsWith<QuickJsException> { rt.openPipe("anything") }
            assertTrue(e.message!!.contains("not loaded"), e.message)
        } finally {
            rt.close()
        }
    }

    @Test
    fun evalEngine_is_one_shot() = runTest {
        val rt = QuickJsRuntime.create(appVersion = 1)
        try {
            rt.evalEngine(TEST_ENGINE_JS)
            val e = assertFailsWith<QuickJsException> { rt.evalEngine(TEST_ENGINE_JS) }
            assertTrue(e.message!!.contains("already loaded"), e.message)
        } finally {
            rt.close()
        }
    }

    @Test
    fun a_failed_eval_consumes_the_one_shot() = runTest {
        val rt = QuickJsRuntime.create(appVersion = 1)
        try {
            assertFailsWith<QuickJsException> { rt.evalEngine("throw new Error('bad bundle')") }
            assertFailsWith<QuickJsException> { rt.evalEngine(TEST_ENGINE_JS) }
        } finally {
            rt.close()
        }
    }

    @Test
    fun handlers_registered_before_the_eval_serve_the_split_flow() = runTest {
        val rt = QuickJsRuntime.create(appVersion = 7)
        try {
            rt.onAccept("echo-cmd") { pipe ->
                val buf = okio.Buffer()
                while (pipe.source.receive(buf, 4096L) != -1L) { /* to EOF */ }
                pipe.sink.send(buf, buf.size)
                pipe.sink.close()
                pipe.source.close()
            }
            rt.evalEngine(TEST_ENGINE_JS)
            assertEquals("7", rt.load("String(versions.app)"))
        } finally {
            rt.close()
        }
    }
}
