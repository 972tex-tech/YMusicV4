package kmphttp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The network-interceptor bridge runs the async chain under a job linked to the caller. That
 * linkage must be ONE-WAY: a failure inside the bridge (a network interceptor throwing, a reset
 * mid-exchange, …) must surface from execute() as the original exception — not cancel the
 * caller's job tree and get swallowed at the then-cancelled continuation, which is what
 * runBlocking(parent) did.
 *
 * OkHttp only reaches network interceptors on a CONNECTED socket, so the test listens on a real
 * port (the backlog completes the connect; no accept needed) and fails inside the interceptor.
 */
class NetworkInterceptorBridgeTest {

    @Test
    fun interceptor_failure_surfaces_and_does_not_cancel_sibling_work() = runTest {
        ServerSocket(0).use { server ->
            val client = OkKmpHttpClient.Builder()
                .apply {
                    networkInterceptors += AsyncInterceptor { throw IOException("boom") }
                }
                .build()
            val request = RequestBuilder()
                .url("http://127.0.0.1:${server.localPort}/".toHttpUrl())
                .build()

            val sibling = CompletableDeferred<Unit>()
            coroutineScope {
                launch(Dispatchers.Default) { sibling.await() } // must survive the failed call
                try {
                    client.execute(request)
                    fail("execute() must rethrow the interceptor's failure")
                } catch (e: IOException) {
                    assertTrue(e.message == "boom", "expected the interceptor's exception, got $e")
                }
                assertTrue(coroutineContext.isActive, "caller scope must not be cancelled by the failure")
                sibling.complete(Unit)
            }
        }
    }
}
