package kmphttp

import kotlin.time.Duration.Companion.seconds

/**
 * Runs the shared [SslTest] suite against the explicit libcurl engine [CurlKmpHttpClient]. This is
 * how curl's SSL path gets covered on macOS (where the default client is NSURLSession); on Linux it
 * overlaps the default and is harmless.
 */
class CurlSslTest : SslTest() {

    override fun newClient(configure: KmpHttpClient.BaseBuilder<*>.() -> Unit): KmpHttpClient =
        CurlKmpHttpClient.Builder().apply {
            defaultExecuteTimeout = 30.seconds
            configure()
        }.build()
}
