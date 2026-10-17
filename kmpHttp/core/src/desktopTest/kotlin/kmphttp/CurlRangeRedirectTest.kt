package kmphttp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** [assertRangeSurvivesRedirect] against the explicit libcurl engine, which macOS doesn't default to. */
class CurlRangeRedirectTest {

    @Test
    fun testRangeSurvivesRedirect() = runTest {
        val client = CurlKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 30.seconds
            sslConfig = testServerSslConfig()
        }.build()
        assertRangeSurvivesRedirect(client)
    }
}
