package kmphttp

import okhttp3.internal.http2.StreamResetException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// InterruptedIOException covers SocketTimeoutException and OkHttp's call timeout; SocketException covers
// connect refused, no route, connection reset and software-caused abort.
actual fun Throwable.isTransportFailure(): Boolean = when (this) {
    is HttpTimeoutException,
    is InterruptedIOException,
    is UnknownHostException,
    is SocketException,
    is SSLException,
    is StreamResetException,
    -> true
    else -> false
}
