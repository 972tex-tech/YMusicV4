package kmphttp

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Applies this [SslConfig] to [builder]. When the config is the default
 * (system CAs only, no pins, no client cert) the platform default TLS behaviour is left
 * untouched.
 */
internal fun SslConfig.applyTo(builder: OkHttpClient.Builder) {
    if (pinCertificates.isNotEmpty()) {
        builder.certificatePinner(
            CertificatePinner.Builder()
                .apply {
                    pinCertificates.forEach { (host, pins) -> add(host, *pins.toTypedArray()) }
                }
                .build()
        )
    }

    val customTrust = trustedCAs.isNotEmpty() || !trustSystemDefaultCAs
    if (!customTrust && clientCerts.isEmpty()) return

    val trustManager = buildTrustManager()
    val keyManagers = if (clientCerts.isEmpty()) null else buildKeyManagers(clientCerts)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers, arrayOf<TrustManager>(trustManager), SecureRandom())
    }
    builder.sslSocketFactory(sslContext.socketFactory, trustManager)
}

/** Builds an [X509TrustManager] from [SslConfig.trustedCAs] and [SslConfig.trustSystemDefaultCAs]. */
private fun SslConfig.buildTrustManager(): X509TrustManager {
    // No extra anchors: use the platform default trust manager directly.
    // (SslConfig's constructor guarantees trustSystemDefaultCAs is true when trustedCAs is empty.)
    if (trustedCAs.isEmpty()) return systemTrustManager()

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    var index = 0

    if (trustSystemDefaultCAs) {
        systemTrustManager().acceptedIssuers.forEach { cert ->
            keyStore.setCertificateEntry("system-${index++}", cert)
        }
    }

    val certificateFactory = CertificateFactory.getInstance("X.509")
    trustedCAs.forEach { ca ->
        val cert = ca.toByteArray().inputStream().use { certificateFactory.generateCertificate(it) }
        keyStore.setCertificateEntry("ca-${index++}", cert)
    }

    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("No X509TrustManager produced for the configured trust anchors")
}

/**
 * Builds the key managers for mutual TLS from the first client cert variant (JVM supports both
 * PKCS#12 and PEM).
 */
private fun buildKeyManagers(clientCerts: List<SslConfig.ClientCert>): Array<KeyManager> {
    return when (val cert = clientCerts.first()) {
        is SslConfig.ClientCert.Pkcs12 -> {
            val password = cert.password.toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                cert.data.toByteArray().inputStream().use { load(it, password) }
            }
            keyManagersFor(keyStore, password)
        }
        is SslConfig.ClientCert.Pem -> {
            val chain = parseCertificates(cert.certificateChain.toByteArray())
            val key = loadPrivateKey(parseSinglePemBlock(cert.privateKey.toByteArray()))
            keyManagersFor(inMemoryKeyStore(key, chain), NO_PASSWORD)
        }
    }
}

private val NO_PASSWORD = CharArray(0)

private fun keyManagersFor(keyStore: KeyStore, password: CharArray): Array<KeyManager> {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, password)
    return factory.keyManagers
}

private fun inMemoryKeyStore(
    key: java.security.PrivateKey,
    chain: Array<X509Certificate>
): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
    load(null, null)
    setKeyEntry("client", key, NO_PASSWORD, chain)
}

private fun parseCertificates(bytes: ByteArray): Array<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    val certs = bytes.inputStream().use { factory.generateCertificates(it) }
    require(certs.isNotEmpty()) { "Client certificate chain is empty" }
    return certs.map { it as X509Certificate }.toTypedArray()
}

/** Decodes a PKCS#8 private key (DER), trying common algorithms (the spec doesn't carry one). */
private fun loadPrivateKey(pkcs8Der: ByteArray): java.security.PrivateKey {
    val spec = PKCS8EncodedKeySpec(pkcs8Der)
    val attemptFailures = mutableListOf<InvalidKeySpecException>()
    for (algorithm in arrayOf("RSA", "EC")) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(spec)
        } catch (e: InvalidKeySpecException) {
            attemptFailures += e
        }
    }
    throw IllegalArgumentException(
        "Unsupported client private key: expected PKCS#8 RSA or EC",
        attemptFailures.lastOrNull(),
    ).apply { attemptFailures.dropLast(1).forEach(::addSuppressed) }
}

/** Extracts the DER bytes from a single-block PEM (e.g. a PRIVATE KEY). */
private fun parseSinglePemBlock(pem: ByteArray): ByteArray {
    val text = pem.decodeToString()
    val base64 = text.lineSequence()
        .filterNot { it.startsWith("-----") }
        .joinToString("")
        .filter { !it.isWhitespace() }
    return java.util.Base64.getDecoder().decode(base64)
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("No system X509TrustManager available")
}
