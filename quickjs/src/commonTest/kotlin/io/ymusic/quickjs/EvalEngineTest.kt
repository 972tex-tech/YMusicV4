package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The create(script, configure) boot contract. */
class EvalEngineTest {

    @Test
    fun configure_registers_handlers_before_the_boot_eval() = runTest {
        var configured = false
        val rt = QuickJsRuntime.create(TEST_ENGINE_JS, appVersion = 7) { r ->
            configured = true
            r.onAccept("host-cmd") { pipe ->
                pipe.sink.close()
                pipe.source.close()
            }
        }
        try {
            assertEquals(true, configured)
            assertEquals("7", rt.load("String(versions.app)"))
            // BACKEND_VERSION/APP_VERSION are compile-time defines — inlined, gone at runtime;
            // in-bundle use is covered by the lifecycle fixtures/init head.
        } finally {
            rt.close()
        }
    }

    @Test
    fun a_failing_boot_eval_throws_and_does_not_leak() = runTest {
        assertFailsWith<QuickJsException> {
            QuickJsRuntime.create("throw new Error('bad bundle')")
        }
    }

    @Test
    fun a_throwing_configure_fails_the_create() = runTest {
        assertFailsWith<IllegalStateException> {
            QuickJsRuntime.create(TEST_ENGINE_JS) { error("wiring failed") }
        }
    }
}
