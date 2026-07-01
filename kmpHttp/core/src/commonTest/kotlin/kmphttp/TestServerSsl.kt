package kmphttp

import okio.ByteString.Companion.decodeBase64

/**
 * An [SslConfig] that trusts the local [TestServer]'s self-signed leaf (anchors-only). Every smoke /
 * cookie test hits the local server over HTTPS, so this both makes the connection succeed and
 * exercises the custom-trusted-CA path on every engine — including the headless iOS simulator, whose
 * SecTrust can't validate arbitrary certs but accepts the leaf via the offline host-match fallback.
 */
internal fun testServerSslConfig(): SslConfig = SslConfig(
    trustedCAs = listOf(TestServer.serverCertDer.decodeBase64()!!),
    trustSystemDefaultCAs = false,
)
