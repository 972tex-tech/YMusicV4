package kmphttp

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * TLS configuration for a [KmpHttpClient].
 *
 * Not every platform supports every option. Engines should apply what they can and
 * ignore (or warn about) the rest — e.g. a browser engine has no control over trust
 * anchors, pinning, or client certificates and will ignore this entirely.
 *
 * The default instance ([trustSystemDefaultCAs] = true, everything else empty) means
 * "use the platform default TLS behaviour" — engines should not install a custom
 * `SSLSocketFactory` in that case.
 */
class SslConfig(
    /**
     * Extra trusted CA certificates, each an X.509 certificate in DER or PEM encoding.
     * These are trusted *in addition to* the system defaults when [trustSystemDefaultCAs]
     * is true, or as the *only* trust anchors when it is false.
     */
    val trustedCAs: List<ByteString> = emptyList(),
    /**
     * Whether to also trust the platform's system default CAs.
     *
     * Setting this to false with an empty [trustedCAs] would trust nothing, which is almost
     * always a misconfiguration and is rejected at construction.
     */
    val trustSystemDefaultCAs: Boolean = true,
    /**
     * Certificate pins keyed by host pattern (e.g. `"api.example.com"`, `"*.example.com"`).
     * Each value is a list of pins in the `"sha256/<base64>"` form used by OkHttp's
     * `CertificatePinner`.
     */
    val pinCertificates: Map<String, List<String>> = emptyMap(),
    /**
     * Client certificate(s) for mutual TLS, in one or more formats. Each platform picks the first
     * variant it supports natively (no cross-format conversion); supply whichever formats cover
     * the targets you ship. If this is non-empty but a platform supports none of the variants,
     * that's a fatal error at apply time.
     *
     * Native format support:
     * - [ClientCert.Pkcs12] — JVM/Android, Apple
     * - [ClientCert.Pem] — JVM/Android, curl (desktop-native); use [ClientCert.Pem.fromDer] for DER
     */
    val clientCerts: List<ClientCert> = emptyList()
) {
    /** A client certificate (private key + certificate chain) for mutual TLS. */
    sealed class ClientCert {
        /**
         * PKCS#12 archive (key + cert chain). For older Android (whose bundled BouncyCastle can't
         * parse modern OpenSSL 3.x PBES2 algorithms), export with legacy PBE
         * (`openssl pkcs12 -export -legacy ...`).
         */
        class Pkcs12(val data: ByteString, val password: String) : ClientCert()

        /** PEM-encoded (unencrypted) private key + PEM certificate chain. */
        class Pem(val privateKey: ByteString, val certificateChain: ByteString) : ClientCert() {
            companion object {
                /**
                 * Builds a PEM client cert from DER inputs: a PKCS#8 (unencrypted) private key and a
                 * single X.509 certificate. DER -> PEM is a lossless base64 wrap (no crypto).
                 */
                fun fromDer(privateKey: ByteString, certificate: ByteString): Pem = Pem(
                    privateKey = privateKey.derToPem("PRIVATE KEY"),
                    certificateChain = certificate.derToPem("CERTIFICATE")
                )
            }
        }
    }

    init {
        require(trustedCAs.isNotEmpty() || trustSystemDefaultCAs) {
            "SslConfig trusts no CAs: trustedCAs is empty and trustSystemDefaultCAs is false"
        }
    }
}

/**
 * Logs a warning (does not throw) if [SslConfig.clientCerts] is non-empty but the [engine] supports
 * none of the provided variants per [isSupported]. Called from KmpHttpClient constructors so a
 * mismatched client-cert format degrades to "no mutual TLS" with a heads-up rather than a crash.
 */
fun SslConfig.warnIfClientCertsUnsupported(
    engine: String,
    logger: kmpcommon.log.Logger? = null,
    isSupported: (SslConfig.ClientCert) -> Boolean
) {
    if (clientCerts.isNotEmpty() && clientCerts.none(isSupported)) {
        val message = "$engine supports none of the configured client certificate formats " +
            "(${clientCerts.map { it::class.simpleName }}); mutual TLS will not be configured."
        logger?.log(kmpcommon.log.Logger.Level.WARN, message) ?: println("kmpHttp: $message")
    }
}

/** Wraps DER bytes as a PEM block (base64 body, 64-char lines) with the given [label]. */
private fun ByteString.derToPem(label: String): ByteString {
    val body = base64().chunked(64).joinToString("\n")
    return "-----BEGIN $label-----\n$body\n-----END $label-----\n".encodeUtf8()
}
