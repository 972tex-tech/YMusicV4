package ymusic.ytmedia.sabr

import okio.BufferedSource
import okio.Closeable

/**
 * HTTP transport injected by the caller — :yt-media does not depend on any HTTP client. The
 * implementation MUST NOT follow redirects (SABR signals redirects as 3xx + Location and via
 * UMP part 43, both handled by [SabrSession]) and must expose the response body as a streaming
 * [BufferedSource] (UMP responses are large and parsed incrementally).
 */
fun interface SabrHttp {
    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): SabrHttpResponse
}

class SabrHttpResponse(
    val status: Int,
    /** `Location` header when [status] is a redirect. */
    val location: String?,
    /** Streaming response body; [SabrSession] closes it. */
    val body: BufferedSource,
    private val onClose: () -> Unit = {},
) : Closeable {
    val isRedirect: Boolean get() = status in 300..399
    val isSuccessful: Boolean get() = status in 200..299

    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}
