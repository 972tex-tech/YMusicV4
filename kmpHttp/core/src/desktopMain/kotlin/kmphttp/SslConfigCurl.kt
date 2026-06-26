package kmphttp

import kmphttp.curl.EasyHandle
import okio.ByteString

/**
 * An [SslConfig] pre-digested into the exact byte/string forms curl needs, computed **once per
 * client** so nothing is re-derived per request. [apply] just hands the cached values to each easy
 * handle (curl copies the blobs, so they're safe to reuse). The only per-request work left is
 * host-based pin *matching*, which genuinely depends on the request URL.
 *
 * Mapping:
 * - [SslConfig.clientCerts] -> CURLOPT_SSLCERT_BLOB/SSLKEY_BLOB, first PEM variant (curl's OpenSSL
 *   backend can't load PKCS#12). Pre-extracted to PEM bytes.
 * - [SslConfig.trustedCAs] -> when [SslConfig.trustSystemDefaultCAs] is true, added to curl's default
 *   OpenSSL trust store (X509_STORE_add_cert) so they're trusted *alongside* the system roots; when
 *   false, CURLOPT_CAINFO_BLOB *replaces* the store with only these CAs. Pre-converted to PEM.
 * - [SslConfig.pinCertificates] -> CURLOPT_PINNEDPUBLICKEY (host-resolved). Pins pre-formatted; only
 *   the host match runs per request.
 */
internal class CurlSslConfig(sslConfig: SslConfig) {

    /** (PEM cert chain, PEM private key) for the first PEM client cert, or null. */
    private val clientCertPem: Pair<ByteArray, ByteArray>? =
        (sslConfig.clientCerts.firstOrNull { it is SslConfig.ClientCert.Pem } as SslConfig.ClientCert.Pem?)
            ?.let { it.certificateChain.toByteArray() to it.privateKey.toByteArray() }

    /** Custom CAs to add on top of the system defaults (trustSystemDefaultCAs = true), else null. */
    private val extraCaPems: List<ByteArray>?

    /** PEM bundle to replace the trust store with (trustSystemDefaultCAs = false), else null. */
    private val replacementCaBundle: ByteArray?

    init {
        when {
            sslConfig.trustedCAs.isEmpty() -> {
                extraCaPems = null
                replacementCaBundle = null
            }
            sslConfig.trustSystemDefaultCAs -> {
                extraCaPems = sslConfig.trustedCAs.map { it.toPemCertificate().encodeToByteArray() }
                replacementCaBundle = null
            }
            else -> {
                extraCaPems = null
                replacementCaBundle =
                    sslConfig.trustedCAs.joinToString("\n") { it.toPemCertificate() }.encodeToByteArray()
            }
        }
    }

    /** Host pattern -> pre-formatted curl pin values ("sha256//<b64>"). */
    private val pinPatterns: List<Pair<String, List<String>>> =
        sslConfig.pinCertificates.entries.map { (pattern, pins) ->
            // OkHttp "sha256/<b64>" -> curl "sha256//<b64>".
            pattern to pins.map { "sha256//" + it.removePrefix("sha256/") }
        }

    /** Applies the cached config to [handle] for a request to [host]. */
    fun apply(handle: EasyHandle, host: String) {
        clientCertPem?.let { (cert, key) -> handle.setClientCertPem(cert, key) }
        extraCaPems?.let { handle.addCaCertsToDefaultStore(it) }
        replacementCaBundle?.let { handle.setCaInfoBlob(it) }

        if (pinPatterns.isNotEmpty()) {
            val pins = pinPatterns
                .filter { (pattern, _) -> hostMatchesPinPattern(host, pattern) }
                .flatMap { it.second }
            if (pins.isNotEmpty()) handle.setPinnedPublicKey(pins.joinToString(";"))
        }
    }
}

/** Returns the certificate as PEM, converting from DER if it isn't already PEM text. */
private fun ByteString.toPemCertificate(): String {
    val asText = runCatching { utf8() }.getOrNull()
    if (asText != null && asText.contains("-----BEGIN CERTIFICATE-----")) return asText.trim()
    val wrapped = base64().chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----"
}

/** OkHttp-style host pattern matching for pin keys: exact, `*.` (one label), `**.` (any depth). */
private fun hostMatchesPinPattern(host: String, pattern: String): Boolean = when {
    pattern.startsWith("**.") -> {
        val suffix = pattern.substring(3)
        host == suffix || host.endsWith(".$suffix")
    }
    pattern.startsWith("*.") -> {
        val suffix = pattern.substring(2)
        val prefix = if (host.endsWith(".$suffix")) host.dropLast(suffix.length + 1) else ""
        prefix.isNotEmpty() && !prefix.contains('.')
    }
    else -> host.equals(pattern, ignoreCase = true)
}
