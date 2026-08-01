package ymusic.ytmedia.proxy

import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.byteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64

/**
 * EXPERIMENT / spike (JVM, desktop-first): the loopback media proxy the WebView's media requests would be
 * routed through (see `docs/player/youtube-playback.md`). webview-js already rewrites a googlevideo
 * `/videoplayback` request into `<origin>/ymusicForward?url=…&method=…&body=<base64>` (skip_ads.ts:349);
 * repointed at `http://127.0.0.1:<port>`, this server receives that GET, reconstructs the real request,
 * and returns bytes — the one interception mechanism that works on every WebView (Android/iOS/JVM),
 * because it needs no https interception (which WKWebView can't do).
 *
 * This first cut proves the plumbing: a raw `ServerSocket` HTTP/1.1 server on loopback with two routes —
 * `/hello` (a Kotlin-synthesized response) and `/ymusicForward` (transparent forward via [KmpHttpClient]).
 * The cache seam (try `MediaCache.serveSabr`/`serveProgressive` before the network) drops in where
 * [forward] issues the upstream request.
 */
class LocalMediaProxy(
    private val client: KmpHttpClient,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val port: Int get() = server?.localPort ?: error("proxy not started")
    val origin: String get() = "http://127.0.0.1:$port"

    fun start() {
        val srv = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = srv
        scope.launch {
            while (isActive) {
                val socket = try {
                    runInterruptible { srv.accept() }
                } catch (t: Throwable) {
                    if (isActive) log("accept failed: ${t.message}")
                    break
                }
                launch { runCatching { handle(socket) }.onFailure { log("handle failed: ${it.message}") } }
            }
        }
        log("listening on $origin")
    }

    private suspend fun handle(socket: Socket) = socket.use {
        // Request line + headers are ASCII; read them, ignore the headers for this spike.
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return@use
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrElse(1) { "/" }
        val out = socket.getOutputStream()
        when {
            target.startsWith("/hello") ->
                writeResponse(out, 200, "text/plain; charset=utf-8", "hello from LocalMediaProxy".encodeToByteArray())
            target.startsWith("/ymusicForward") -> forward(target, out)
            else -> writeResponse(out, 404, "text/plain; charset=utf-8", "no route".encodeToByteArray())
        }
    }

    /**
     * Reconstruct and issue the real request packed into the `/ymusicForward` query. This is where the
     * cache lookup will go: try MediaCache first, fall through to this network fetch on a miss.
     */
    private suspend fun forward(target: String, out: OutputStream) {
        val params = target.substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i < 0) null else URLDecoder.decode(pair.take(i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
            .toMap()
        val url = params["url"]
        if (url == null) {
            writeResponse(out, 400, "text/plain; charset=utf-8", "missing url".encodeToByteArray())
            return
        }
        val method = params["method"] ?: "GET"
        val body = params["body"]?.let { Base64.getDecoder().decode(it) }
        log("forward $method $url" + (body?.let { " (${it.size} body bytes)" } ?: ""))

        val builder = RequestBuilder().url(url)
        if (body != null) builder.method(method, body.toRequestBody(null)) else builder.get()
        val response = client.execute(builder.build(), RedirectOptions(followRedirects = true, followSslRedirects = true))
        try {
            val bytes = response.body.byteString().toByteArray()
            val contentType = runCatching { response.body.contentType()?.toString() }.getOrNull()
                ?: "application/octet-stream"
            writeResponse(out, response.code, contentType, bytes)
        } finally {
            response.close()
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(" OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n") // the WebView fetch is cross-origin to loopback
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    fun stop() {
        scope.cancel()
        server?.close()
        server = null
    }
}
