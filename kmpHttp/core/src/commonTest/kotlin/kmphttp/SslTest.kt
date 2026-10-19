package kmphttp

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.IOException
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * One cross-platform [SslConfig] suite for the default [KmpHttpClient] on every engine
 * (OkHttp / NSURLSession / libcurl) — the whole point of the library: write the TLS contract once,
 * verify it everywhere.
 *
 * The self-signed / pinning / mutual-TLS cases run against the local [TestServer] (a self-signed
 * leaf with SAN = the host LAN IP, plus a mutual-TLS port), so there's no dependency on a live public
 * host. Its cert set is provided by the `:startTestServer` Gradle task. Client certs are given in BOTH
 * supported formats ([SslConfig.ClientCert.Pkcs12] for OkHttp/NSURLSession, [SslConfig.ClientCert.Pem]
 * for libcurl); each engine picks the variant it supports.
 */
open class SslTest {

    /** Builds the client under test. Subclasses override to pin a specific engine. */
    protected open fun newClient(configure: KmpHttpClient.BaseBuilder<*>.() -> Unit = {}) =
        KmpHttpClient.create {
            defaultExecuteTimeout = 30.seconds
            configure()
        }

    /**
     * Whether to skip the mutual-TLS case. Only NSURLSession on **macOS** is affected: its
     * `SecPKCS12Import` imports into the login keychain and can block on a headless host (iOS is
     * transient, and OkHttp/libcurl don't touch the keychain). Subclasses on a keychain-free engine
     * override this to `false`.
     */
    protected fun skipMutualTls(): Boolean = isMacosHost()

    private fun get(url: String) = RequestBuilder().url(url).get().build()

    @Test
    fun rejectsTrustingNothing() {
        // Empty trustedCAs + no system CAs would trust nothing -> rejected at construction.
        assertFailsWith<IllegalArgumentException> { SslConfig(trustSystemDefaultCAs = false) }
    }

    @Test
    fun selfSignedRejectedByDefault() = runTest {
        // The test server is self-signed; with only the system store it must be rejected.
        assertFailsWith<IOException> {
            newClient().execute(get(TestServer.baseUrl))
        }
    }

    @Test
    fun selfSignedTrustedViaAnchor() = runTest {
        // Trust ONLY this cert (anchors-only): the canonical way to accept a specific self-signed
        // server (trustSystemDefaultCAs = false).
        val cert = TestServer.serverCertDer.decodeBase64()!!
        val client = newClient {
            sslConfig = SslConfig(trustedCAs = listOf(cert), trustSystemDefaultCAs = false)
        }
        client.execute(get(TestServer.baseUrl)).use { assertEquals(200, it.code) }
    }

    @Test
    fun correctPinConnects() = runTest {
        val client = newClient {
            sslConfig = SslConfig(
                // The server is self-signed, so trust its leaf for base validation; the pin (the
                // point of this test) is enforced on top.
                trustedCAs = listOf(TestServer.serverCertDer.decodeBase64()!!),
                trustSystemDefaultCAs = false,
                pinCertificates = mapOf(TestServer.host to listOf(TestServer.serverPinSha256)),
            )
        }
        client.execute(get(TestServer.baseUrl)).use { assertEquals(200, it.code) }
    }

    @Test
    fun wrongPinFails() = runTest {
        val bogusPin = "sha256/" + ByteArray(32).toByteString().base64()
        val client = newClient {
            // Base trust is fine (leaf anchored); the connection must still fail on the bad pin.
            sslConfig = SslConfig(
                trustedCAs = listOf(TestServer.serverCertDer.decodeBase64()!!),
                trustSystemDefaultCAs = false,
                pinCertificates = mapOf(TestServer.host to listOf(bogusPin)),
            )
        }
        assertFailsWith<IOException> { client.execute(get(TestServer.baseUrl)) }
    }

    @Test
    open fun mutualTlsWithClientCert() = runTest {
        if (skipMutualTls()) return@runTest

        val client = newClient {
            sslConfig = SslConfig(
                // Trust the self-signed server leaf; present the client identity the mTLS port requires.
                trustedCAs = listOf(TestServer.serverCertDer.decodeBase64()!!),
                trustSystemDefaultCAs = false,
                clientCerts = listOf(
                    SslConfig.ClientCert.Pkcs12(TestServer.clientP12.decodeBase64()!!, "kmphttp"),
                    SslConfig.ClientCert.Pem(
                        privateKey = TestServer.clientKeyPem.decodeBase64()!!,
                        certificateChain = TestServer.clientCertPem.decodeBase64()!!,
                    ),
                )
            )
        }
        client.execute(get(TestServer.mtlsUrl)).use { assertEquals(200, it.code) }
    }
}
