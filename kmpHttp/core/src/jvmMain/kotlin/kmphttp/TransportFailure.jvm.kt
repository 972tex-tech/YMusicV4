package kmphttp

import okhttp3.internal.http2.StreamResetException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

// InterruptedIOException covers SocketTimeoutException and OkHttp's call timeout; SocketException covers
// connect refused, no route, connection reset and software-caused abort.
actual fun Throwable.isTransportFailure(): Boolean = when (this) {
    is HttpTimeoutException,
    is InterruptedIOException,
    is UnknownHostException,
    is StreamResetException,
    -> true
    is SocketException -> !isFileDescriptorExhaustion()
    is SSLPeerUnverifiedException -> false
    is SSLException -> !hasCertificateCause()
    else -> isPeerClosedBeforeHead()
}

// OkHttp's "unexpected end of stream on <url>": the peer closed the connection before the response head.
private fun Throwable.isPeerClosedBeforeHead(): Boolean =
    this::class == IOException::class && cause is EOFException && message?.startsWith("unexpected end of stream") == true

// EMFILE/ENFILE surface as a SocketException but are a local resource leak, not the network.
private fun SocketException.isFileDescriptorExhaustion(): Boolean {
    val text = message ?: return false
    return "Too many open files" in text || "EMFILE" in text || "ENFILE" in text
}

private fun Throwable.hasCertificateCause(): Boolean = generateSequence(cause) { it.cause }.any { it is CertificateException }
