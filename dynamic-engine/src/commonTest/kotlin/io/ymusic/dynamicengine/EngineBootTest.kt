package io.ymusic.dynamicengine

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The boot contract: eval + "init" health check, and the typed errors when either fails. */
class EngineBootTest {

    @Test
    fun a_healthy_boot_reports_the_bundle_backend_version() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            assertTrue((e.backendVersion ?: 0) >= 1, "backendVersion=${e.backendVersion}")
        } finally {
            e.close()
        }
    }

    @Test
    fun a_broken_bundle_fails_typed_as_E20020() = runTest {
        val err = assertFailsWith<EngineError.Technical> {
            withContext(testEngineDispatcher) {
                DynamicEngine.create(
                    FakeHttpClient(CannedResponse.ofText("")),
                    appVersion = 1,
                    dispatcher = testEngineDispatcher,
                    engineJs = "throw new Error('corrupt download')",
                )
            }
        }
        assertEquals("E20020", err.code)
    }

    @Test
    fun a_bundle_without_init_fails_the_health_check_as_E20021() = runTest {
        val err = assertFailsWith<EngineError.Technical> {
            withContext(testEngineDispatcher) {
                // evals fine, serves nothing — create succeeds; the health check's init open is refused
                val e = DynamicEngine.create(
                    FakeHttpClient(CannedResponse.ofText("")),
                    appVersion = 1,
                    dispatcher = testEngineDispatcher,
                    engineJs = "1",
                )
                try {
                    e.healthCheck()
                } finally {
                    e.close()
                }
            }
        }
        assertEquals("E20021", err.code)
    }
}
