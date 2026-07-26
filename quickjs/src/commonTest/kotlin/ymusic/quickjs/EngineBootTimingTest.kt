package ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.measureTime

/**
 * A measurement, not a correctness suite: times the whole engine boot (native create + bundle
 * eval + transport capture) for the size-budget question "does bundle growth cost user-visible
 * startup time". Runs wherever the shared suite runs — the JVM number lands in test stdout, the
 * Android one via connectedAndroidTest in the instrumentation output/logcat. The TEST bundle is
 * ~1.5× the prod bundle, so read these as an upper bound; eval scales roughly with size.
 */
class EngineBootTimingTest {

    @Test
    fun measures_bundle_boot_time() = runTest {
        repeat(4) { i ->
            var rt: QuickJsRuntime? = null
            val d = measureTime { rt = QuickJsRuntime.create(TEST_ENGINE_JS) }
            println("engine-boot-timing #$i: ${d.inWholeMilliseconds} ms (bundle ${TEST_ENGINE_JS.length} chars)")
            assertNotNull(rt).close()
        }
    }
}
