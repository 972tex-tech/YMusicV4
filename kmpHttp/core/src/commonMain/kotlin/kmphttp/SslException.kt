package kmphttp

import okio.IOException

/**
 * The peer's TLS identity failed verification (trust anchors, hostname, or certificate pins) —
 * deliberately never retried. On JVM this is `javax.net.ssl.SSLException`, so OkHttp's natural
 * `SSLHandshakeException` / `SSLPeerUnverifiedException` are subtypes and common code catches one
 * type on every platform; the other engines throw it directly with the reason in the message.
 */
expect open class SslException(message: String) : IOException
