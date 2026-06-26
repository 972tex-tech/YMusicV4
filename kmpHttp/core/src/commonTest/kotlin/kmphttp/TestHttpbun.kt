package kmphttp

import okio.ByteString.Companion.decodeBase64

/**
 * An [SslConfig] that trusts httpbun.com's build-generated cert set (stable ISRG Root X1 root +
 * httpbun's current leaf; see the `:generateHttpbunCerts` Gradle task) via anchors-only. Every
 * live test hits httpbun.com, so reusing this both exercises the custom-trusted-CA path on every
 * engine and makes the headless iOS simulator pass — its SecTrust can't validate live public certs,
 * but the root validates the chain elsewhere and the fresh leaf is trusted via the offline
 * host-match fallback.
 */
internal fun httpbunSslConfig(): SslConfig = SslConfig(
    trustedCAs = HttpbunCerts.trustedCaDer.map { it.decodeBase64()!! },
    trustSystemDefaultCAs = false,
)
