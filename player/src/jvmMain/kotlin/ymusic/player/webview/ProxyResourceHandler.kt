package ymusic.player.webview

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
import kotlin.math.min

/**
 * The desktop (JCEF) delivery adapter for the player cache: a `CefResourceHandler` that answers an
 * intercepted `/ymusicForward` request from [proxy] ([LocalMediaProxy.serve] — the shared router). The
 * JCEF resource-handler contract is synchronous, so [processRequest] launches the suspend serve on [scope],
 * buffers the (bounded) response body, then `callback.Continue()`s; headers and bytes are handed back from
 * the buffer. Wire it in from the request handler's `getResourceHandler` for a URL containing `/ymusicForward`.
 */
internal class ProxyResourceHandler(
    private val proxy: LocalMediaProxy,
    private val target: String,
    private val scope: CoroutineScope,
) : CefResourceHandlerAdapter() {

    private var status = 200
    private var mimeType = "application/octet-stream"
    private var headers: List<Pair<String, String>> = emptyList()
    private var data = ByteArray(0)
    private var offset = 0

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        scope.launch {
            try {
                val response = proxy.serve(target)
                status = response.status
                headers = response.headers
                mimeType = response.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
                    ?.second?.substringBefore(';') ?: "application/octet-stream"
                data = drain(response.body) // drain() closes body at EOF, releasing the upstream response
            } catch (t: Throwable) {
                status = 502
                data = ByteArray(0)
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
        responseLength?.set(data.size)
    }

    override fun readResponse(dataOut: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
        if (dataOut == null || offset >= data.size) {
            bytesRead?.set(0)
            return false
        }
        val n = min(bytesToRead, data.size - offset)
        System.arraycopy(data, offset, dataOut, 0, n)
        offset += n
        bytesRead?.set(n)
        return true
    }

    private suspend fun drain(body: kmpcommon.io.AsyncSource): ByteArray {
        val out = Buffer()
        val chunk = Buffer()
        while (true) {
            val n = body.read(chunk, 64 * 1024)
            if (n == -1L) break
            out.write(chunk, chunk.size)
        }
        body.close()
        return out.readByteArray()
    }
}
