package ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import okio.Buffer
import ymusic.dynamicengine.internal.ENGINE_TEST_JS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicEngineManagerTest {

    private fun manager(drainTimeoutMs: Long = 30_000L) = DynamicEngineManager(
        client = FakeHttpClient(CannedResponse.ofText("")),
        appVersion = 1,
        baselineJs = ENGINE_TEST_JS,
        drainTimeoutMs = drainTimeoutMs,
    )

    /** Echo through the prod "ping" diagnostic — a real job on whatever engine is current. */
    private suspend fun DynamicEngineManager.ping(text: String): String = use { e ->
        val pipe = e.openPipe("ping")
        try {
            val head = Buffer().writeUtf8(text)
            pipe.sink.send(head, head.size)
            pipe.sink.close()
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.source.close()
        }
    }

    @Test
    fun start_then_swap_flips_and_retires_gracefully() = runTest {
        val m = manager()
        try {
            val v0 = m.start()
            assertEquals("hello", m.ping("hello"))
            val v1 = m.swap(ENGINE_TEST_JS)
            assertEquals(v0, v1) // same bundle, same self-declared revision
            assertTrue(m.lastRetirementDrained, "an idle engine must drain, not hit the deadline")
            assertEquals("still-up", m.ping("still-up"))
        } finally {
            m.close()
        }
    }

    @Test
    fun a_rejected_swap_keeps_the_current_engine_serving() = runTest {
        val m = manager()
        try {
            m.start()
            val err = assertFailsWith<EngineError.Technical> { m.swap("throw new Error('bad push')") }
            assertEquals("E20020", err.code)
            assertEquals("survived", m.ping("survived"))
        } finally {
            m.close()
        }
    }

    @Test
    fun swap_drains_an_engine_with_a_live_cron() = runTest {
        val m = manager()
        try {
            m.start()
            m.use { e ->
                val pipe = e.openPipe("test/lifecycle/start-cron")
                val head = Buffer().writeUtf8("{}")
                pipe.sink.send(head, head.size)
                val out = Buffer()
                while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
                assertEquals("started", out.readUtf8())
                pipe.sink.close()
                pipe.source.close()
            }
            m.swap(ENGINE_TEST_JS) // the 60s interval must not hold retirement to the deadline
            assertTrue(m.lastRetirementDrained, "shutdown must clear the cron so the engine drains")
        } finally {
            m.close()
        }
    }

    @Test
    fun a_wedged_engine_is_force_closed_at_the_deadline() = runTest {
        val m = manager(drainTimeoutMs = 500L)
        try {
            m.start()
            // A pipe "shutdown" cannot clear (the fixture just holds it) wedges the drain.
            m.use { e -> e.openPipe("test/keep") }
            m.swap(ENGINE_TEST_JS)
            assertFalse(m.lastRetirementDrained)
            assertEquals("recovered", m.ping("recovered")) // new engine unaffected
        } finally {
            m.close()
        }
    }

    @Test
    fun a_bad_remote_bundle_falls_back_to_the_baseline_at_start() = runTest {
        val m = manager()
        try {
            val v = m.start(remoteJs = "throw new Error('corrupt download')")
            assertTrue(v >= 1)
            assertEquals("baseline", m.ping("baseline"))
        } finally {
            m.close()
        }
    }
}
