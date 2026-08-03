package ymusic.player.webview

import kmpcommon.io.AsyncSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Buffer
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import ymusic.ytmedia.proxy.LocalMediaProxy

/**
 * The desktop (JCEF) delivery adapter for the player cache: a `CefResourceHandler` that answers an
 * intercepted `/ymusicForward` request from [proxy] ([LocalMediaProxy.serve] — the shared router) and
 * **streams** the body through. A media response is tens of MB, so it must never sit fully in memory:
 * each time CEF wants more, one chunk is pulled off the [ProxyResponse.body]; if none is buffered yet the
 * read returns 0 bytes and CEF is told to wait ([CefCallback.Continue] fires once the chunk lands). Wire
 * it in from the request handler's `getResourceHandler` for a URL containing `/ymusicForward`.
 */
internal class ProxyResourceHandler(
    private val proxy: LocalMediaProxy,
    private val target: String,
    private val scope: CoroutineScope,
) : CefResourceHandlerAdapter() {

    // Header fields: written in [processRequest]'s coroutine before its Continue(), read by CEF in
    // [getResponseHeaders] after it — the Continue() hand-off publishes them.
    private var status = 200
    private var mimeType = "application/octet-stream"
    private var headers: List<Pair<String, String>> = emptyList()
    private var contentLength = -1L

    // The response body: set once (headers resolve), nulled at EOF / cancel. Only the reference crosses
    // threads, so volatile is enough — the blocking read runs off-lock on a pull coroutine.
    @Volatile private var body: AsyncSource? = null

    // Pulled-but-not-yet-served bytes. An okio Buffer is not thread-safe and the pull coroutine writes it
    // while a CEF thread reads it, so all access is under [lock] (which also guards [eof]).
    private val lock = Any()
    private val ready = Buffer()
    private var eof = false

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        scope.launch {
            try {
                val response = proxy.serve(target)
                status = response.status
                headers = response.headers
                mimeType = response.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
                    ?.second?.substringBefore(';') ?: "application/octet-stream"
                contentLength = response.contentLength ?: -1L
                body = response.body
            } catch (t: Throwable) {
                status = 502
                synchronized(lock) { eof = true }
            }
            callback?.Continue()
        }
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        response?.let { r ->
            r.status = status
            r.mimeType = mimeType
            headers.forEach { (k, v) -> r.setHeaderByName(k, v, true) }
        }
        // A known length lets CEF size the response; unknown (-1) streams until readResponse signals EOF.
        responseLength?.set(if (contentLength in 0..Int.MAX_VALUE.toLong()) contentLength.toInt() else -1)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        if (dataOut == null) { bytesRead?.set(0); return false }
        synchronized(lock) {
            if (ready.size > 0L) {
                val n = minOf(bytesToRead.toLong(), ready.size).toInt()
                ready.read(dataOut, 0, n)
                bytesRead?.set(n)
                return true
            }
            if (eof) { bytesRead?.set(0); return false } // fully streamed
        }
        // Nothing buffered and not done: pull the next chunk, then have CEF read again. CEF keeps only one
        // readResponse outstanding, so there's never more than one pull in flight.
        scope.launch { pullNext(callback) }
        bytesRead?.set(0)
        return true
    }

    private suspend fun pullNext(callback: CefCallback?) {
        val src = body
        val chunk = Buffer()
        val n = if (src == null) -1L else runCatching { src.read(chunk, CHUNK) }.getOrElse { -1L }
        if (n == -1L) {
            body = null
            synchronized(lock) { eof = true }
            src?.let { runCatching { it.close() } } // release the upstream response at EOF
        } else {
            synchronized(lock) { ready.writeAll(chunk) }
        }
        callback?.Continue()
    }

    override fun cancel() {
        // The WebView aborted the request (seek / stop / navigate) — release the upstream response.
        val src = body
        body = null
        synchronized(lock) { eof = true }
        src?.let { b -> scope.launch { runCatching { b.close() } } }
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
