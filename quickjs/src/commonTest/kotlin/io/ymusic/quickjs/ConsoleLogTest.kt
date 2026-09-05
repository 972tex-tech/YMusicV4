package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The __consoleLog bridge and the unhandled-rejection tracker, through the onLog hook. */
class ConsoleLogTest {

    @Test
    fun console_lines_reach_the_hook_with_their_level() = runTest {
        val lines = mutableListOf<Pair<Int, String>>()
        val rt = QuickJsRuntime.create(TEST_ENGINE_JS, appVersion = 1, onLog = { l, m -> lines.add(l to m) })
        try {
            rt.load("""console.log("a", 1); console.warn("w"); console.error("e")""")
            // Boot of TEST_ENGINE_JS may emit its own console/rejection lines (cache/list, …).
            assertTrue(lines.contains(QuickJsRuntime.LOG_DEBUG to "a 1"), "got: $lines")
            assertTrue(lines.contains(QuickJsRuntime.LOG_WARN to "w"), "got: $lines")
            assertTrue(lines.contains(QuickJsRuntime.LOG_ERROR to "e"), "got: $lines")
        } finally {
            rt.close()
        }
    }

    @Test
    fun an_unhandled_rejection_is_surfaced_on_the_log_channel() = runTest {
        val lines = mutableListOf<Pair<Int, String>>()
        val rt = QuickJsRuntime.create(TEST_ENGINE_JS, appVersion = 1, onLog = { l, m -> lines.add(l to m) })
        try {
            rt.load("Promise.reject(new Error('boom')); 1")
            val rejection = lines.firstOrNull { it.first == QuickJsRuntime.LOG_REJECTION && "boom" in it.second }
            assertNotNull(rejection, "got: $lines")
            assertTrue("    at " in rejection.second, "expected stack, got: ${rejection.second}")
        } finally {
            rt.close()
        }
    }

    @Test
    fun an_unhandled_rejection_of_a_plain_object_includes_the_reject_site() = runTest {
        val lines = mutableListOf<Pair<Int, String>>()
        val rt = QuickJsRuntime.create(TEST_ENGINE_JS, appVersion = 1, onLog = { l, m -> lines.add(l to m) })
        try {
            rt.load("function rejectEmpty() { Promise.reject({}); } rejectEmpty(); 1")
            val rejection = lines.firstOrNull { it.first == QuickJsRuntime.LOG_REJECTION && "rejectEmpty" in it.second }
            assertNotNull(rejection, "got: $lines")
            assertTrue("    at " in rejection.second, "expected reject-site stack, got: ${rejection.second}")
        } finally {
            rt.close()
        }
    }

    @Test
    fun a_throwing_hook_does_not_unwind_into_the_engine() = runTest {
        val rt = QuickJsRuntime.create(TEST_ENGINE_JS, appVersion = 1, onLog = { _, _ -> error("bad hook") })
        try {
            assertEquals("ok", rt.load("console.log('x'); 'ok'"))
        } finally {
            rt.close()
        }
    }
}
