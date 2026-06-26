package kmphttp

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

/**
 * Runs test only on non-browser platforms.
 * Skips on browser (JS/WasmJS browser) where certain features are not available.
 */
fun runTestSkipBrowser(block: suspend () -> Unit): TestResult = runTest {
    if (BUILD_TARGET.isBrowser) {
        println("Skipped: Not supported on browser")
        return@runTest
    }
    block()
}
