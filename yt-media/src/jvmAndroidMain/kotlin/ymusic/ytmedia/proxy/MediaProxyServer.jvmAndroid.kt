package ymusic.ytmedia.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Plain-http loopback transport for desktop (JVM/JCEF) and Android — a `java.net` [ServerSocket] on
 * 127.0.0.1 driving [LocalMediaProxy]. (iOS needs TLS instead; that actual lives in appleMain.) The
 * WebView's rewritten request is a GET with everything in the query, so only the request line is read.
 */
class SocketMediaProxyServer(private val proxy: LocalMediaProxy) : MediaProxyServer {
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val origin: String
        get() = "http://127.0.0.1:${server?.localPort ?: error("proxy not started")}"

    override fun start() {
        val srv = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = srv
        scope.launch {
            while (isActive) {
                val socket = try {
                    runInterruptible { srv.accept() }
                } catch (t: Throwable) {
                    break // listener closed on stop()
                }
                launch { runCatching { handle(socket) } }
            }
        }
    }

    private suspend fun handle(socket: Socket) = socket.use {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return@use
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrElse(1) { "/" }
        val response = proxy.serve(target)
        try {
            writeResponse(socket.getOutputStream(), response)
        } finally {
            response.onClose()
        }
    }

    private suspend fun writeResponse(out: OutputStream, response: ProxyResponse) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Access-Control-Allow-Origin: *\r\n") // the WebView fetch is cross-origin to loopback
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))

        val buffer = Buffer()
        while (true) {
            val n = response.body.read(buffer, 16 * 1024)
            if (n == -1L) break
            out.write(buffer.readByteArray())
        }
        out.flush()
        response.body.close()
    }

    override fun stop() {
        scope.cancel()
        server?.close()
        server = null
    }
}
