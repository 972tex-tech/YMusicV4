package kmphttp

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A server-trust rejection issued by our own SslConfig handler must surface as [SslException] with
 * the host in the message — NOT as NSURLSession's bare "cancelled" (-999), which is all the OS
 * reports when a challenge is cancelled.
 */
class SslExceptionTest {
    @Test
    fun trustRejectionSurfacesAsSslException() = runTest {
        val bogusPin = "sha256/" + ByteArray(32).toByteString().base64()
        val client = KmpHttpClient.create {
            defaultExecuteTimeout = 30.seconds
            sslConfig = SslConfig(
                trustedCAs = listOf(TestServer.serverCertDer.decodeBase64()!!),
                trustSystemDefaultCAs = false,
                pinCertificates = mapOf(TestServer.host to listOf(bogusPin)),
            )
        }
        val e = assertFailsWith<SslException> {
            client.execute(RequestBuilder().url(TestServer.url("/get")).get().build())
        }
        assertTrue(
            e.message.orEmpty().contains("server trust rejected for ${TestServer.host}"),
            "unexpected message: ${e.message}",
        )
    }
}
