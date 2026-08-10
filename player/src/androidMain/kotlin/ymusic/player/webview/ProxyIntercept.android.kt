package ymusic.player.webview

import android.util.Log
import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import okio.Buffer
import ymusic.ytmedia.proxy.LocalMediaProxy
import java.io.ByteArrayInputStream
import java.io.IOException
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
internal fun proxyIntercept(proxy: LocalMediaProxy, target: String): WebResourceResponse = try {
    runBlocking {
        val response = proxy.serve(target)
        val contentType = response.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
        val mime = contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
        val charset = contentType?.substringAfter("charset=", "")?.takeIf { it.isNotEmpty() }
        WebResourceResponse(
            mime,
            charset,
            response.status,
            response.reason.ifEmpty { "OK" },
            response.headers.toMap(),
            AsyncInputStream(response.body),
        )
    }
} catch (e: Throwable) {
    // This runs inside a Chromium JNI callback: ANY escaping Java throwable is process-fatal
    // ([FATAL:jni_android.cc]). A failed forward (googlevideo read timeout mid-playback,
    // observed live) must come back as a response — the page's SABR layer retries failed
    // fetches; a dead process doesn't. An IOException replays through the body stream, where
    // Chromium handles it as a genuine network read failure; anything else serves empty bytes.
    Log.w("ProxyIntercept", "forward failed for $target", e)
    val body: InputStream = if (e is IOException) FailingInputStream(e) else ByteArrayInputStream(byteArrayOf())
    WebResourceResponse("text/plain", null, 504, "Gateway Timeout", emptyMap(), body)
}

/** A body that fails on first read with the forward's own IOException — the failure surfaces to
 *  the page as the network error it was, not as a mysteriously empty 504. */
private class FailingInputStream(private val cause: IOException) : InputStream() {
    override fun read(): Int = throw IOException(cause.message, cause)
    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException(cause.message, cause)
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

    // Chromium consumes this stream from native code and handles IOException as a normal read
    // failure — but ANY other throwable crossing that JNI boundary is process-fatal, so every
    // upstream failure (a mid-body googlevideo timeout is routine) is narrowed to IOException.
    private fun fill(min: Long): Boolean = try {
        runBlocking {
            while (buffer.size < min) {
                val n = body.read(buffer, 64 * 1024)
                if (n == -1L) return@runBlocking buffer.size > 0
            }
            true
        }
    } catch (e: IOException) {
        throw e
    } catch (e: Throwable) {
        throw IOException("proxy body read failed", e)
    }

    override fun close() {
        try {
            runBlocking { body.close() } // releases the upstream network response via the tee / caching source
        } catch (e: Throwable) {
            // Close is best-effort; a failure must not cross into Chromium.
        }
    }
}
