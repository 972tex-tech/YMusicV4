@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class)

package kmphttp

import kmphttp.internal.applyCustomAnchors
import kmphttp.internal.chainContainsTrustedCertForHost
import kmphttp.internal.chainMatchesPins
import kmphttp.internal.clientCertificateCredential
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURLAuthenticationMethodClientCertificate
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef

/** True when this config asks for no TLS customization, i.e. platform defaults apply. */
internal fun SslConfig.isDefault(): Boolean =
    trustedCAs.isEmpty() && trustSystemDefaultCAs && pinCertificates.isEmpty() && clientCerts.isEmpty()

/**
 * Builds an NSURLSession auth-challenge handler that enforces this [SslConfig]:
 * custom trust anchors, certificate pinning, and client certificates (mutual TLS).
 * Challenges this config doesn't handle fall through to [fallback], or default handling.
 *
 * The low-level Security/CoreFoundation interop lives in SecurityInterop.apple.kt.
 */
internal fun SslConfig.toAuthChallengeHandler(fallback: AuthChallengeHandler?): AuthChallengeHandler {
    // Apple only supports PKCS#12 client certs (SecPKCS12Import -> SecIdentity). Pick the first one;
    // configs with no supported variant are warned about at the client constructor.
    val pkcs12Cert = clientCerts.firstOrNull { it is SslConfig.ClientCert.Pkcs12 }
        as SslConfig.ClientCert.Pkcs12?
    // Import the PKCS#12 client cert once and reuse the credential for every handshake. The cert is
    // fixed for the client's lifetime; re-importing per request wastes work (and on macOS re-hits
    // the keychain). Lazy so a client that never faces a client-cert challenge never imports.
    val clientCredential: NSURLCredential? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        pkcs12Cert?.let { clientCertificateCredential(it) }
    }
    return { challenge, completion ->
        val passToFallback = {
            if (fallback != null) fallback(challenge, completion)
            else completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
        when (challenge.protectionSpace.authenticationMethod) {
            NSURLAuthenticationMethodServerTrust -> {
                val customizesTrust =
                    trustedCAs.isNotEmpty() || !trustSystemDefaultCAs || pinCertificates.isNotEmpty()
                val serverTrust = challenge.protectionSpace.serverTrust
                when {
                    !customizesTrust -> passToFallback()
                    serverTrust != null && evaluateServerTrust(serverTrust, challenge.protectionSpace.host) ->
                        completion(
                            NSURLSessionAuthChallengeUseCredential,
                            NSURLCredential.credentialForTrust(serverTrust)
                        )
                    else -> completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                }
            }

            NSURLAuthenticationMethodClientCertificate -> {
                when {
                    pkcs12Cert == null -> passToFallback()
                    clientCredential != null ->
                        completion(NSURLSessionAuthChallengeUseCredential, clientCredential)
                    else -> completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                }
            }

            else -> passToFallback()
        }
    }
}

/** Applies custom anchors (if any), evaluates the chain, then checks pins (if any). */
private fun SslConfig.evaluateServerTrust(trust: SecTrustRef, host: String): Boolean {
    if (trustedCAs.isNotEmpty() || !trustSystemDefaultCAs) {
        if (!applyCustomAnchors(trust, trustedCAs, anchorsOnly = !trustSystemDefaultCAs)) return false
    }

    var trusted = SecTrustEvaluateWithError(trust, null)
    if (!trusted && trustedCAs.isNotEmpty()) {
        // Apple won't accept a self-signed end-entity cert as a CA anchor ("not permitted for this
        // usage"). Fall back to exact-cert trust: accept if the presented chain contains a cert the
        // caller explicitly listed in trustedCAs AND that cert is valid for this host. (Expiry is
        // not re-checked on this path.)
        trusted = chainContainsTrustedCertForHost(trust, trustedCAs, host)
    }
    if (!trusted) return false

    if (pinCertificates.isNotEmpty()) {
        val expected = pinsForHost(host)
        // A host with configured pins must match at least one cert in the chain.
        if (expected.isNotEmpty() && !chainMatchesPins(trust, expected)) return false
    }
    return true
}

/** Resolves the union of pins whose host pattern matches [host]. */
private fun SslConfig.pinsForHost(host: String): Set<String> =
    pinCertificates.entries
        .filter { (pattern, _) -> hostMatchesPinPattern(host, pattern) }
        .flatMap { it.value }
        .toSet()

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
