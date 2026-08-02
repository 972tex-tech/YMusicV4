package ymusic.player.webview

import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import okio.Buffer
import ymusic.ytmedia.proxy.LocalMediaProxy
import java.io.InputStream

/**
 * The Android delivery adapter for the player cache: turns an intercepted `/ymusicForward` request into a
 * [WebResourceResponse] served from [proxy] ([LocalMediaProxy.serve] — the shared router). Call it from
 * `WebViewClient.shouldInterceptRequest` when the URL contains `/ymusicForward` (Android can't read POST
 * bodies there, which is why webview-js packs method+body into the query).
 *
 * `shouldInterceptRequest` runs on a WebView background thread and wants a (blocking) [InputStream], so we
 * `runBlocking` the suspend serve to get headers, then stream the body through a blocking bridge.
 */
internal fun proxyIntercept(proxy: LocalMediaProxy, target: String): WebResourceResponse = runBlocking {
    val response = proxy.serve(target)
    val contentType = response.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
    val mime = contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
    val charset = contentType?.substringAfter("charset=", "")?.takeIf { it.isNotEmpty() }
    val out = WebResourceResponse(
        mime,
        charset,
        response.status,
        response.reason.ifEmpty { "OK" },
        response.headers.toMap(),
        AsyncInputStream(response.body),
    )
    out
}

/** A blocking [InputStream] over the router's suspend body — each read blocks on one async read. */
private class AsyncInputStream(
    private val body: kmpcommon.io.AsyncSource,
) : InputStream() {
    private val buffer = Buffer()

    override fun read(): Int {
        if (!fill(1)) return -1
        return buffer.readByte().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill(1)) return -1
        val n = minOf(len.toLong(), buffer.size).toInt()
        buffer.read(b, off, n)
        return n
    }

    private fun fill(min: Long): Boolean = runBlocking {
        while (buffer.size < min) {
            val n = body.read(buffer, 64 * 1024)
            if (n == -1L) return@runBlocking buffer.size > 0
        }
        true
    }

    override fun close() {
        runBlocking { body.close() } // releases the upstream network response via the tee / caching source
    }
}
