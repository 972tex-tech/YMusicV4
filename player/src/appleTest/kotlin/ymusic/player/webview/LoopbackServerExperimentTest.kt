package ymusic.player.webview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceDate
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Answers "can iOS start a loopback proxy?" — the load-bearing assumption of the WKWebView-can't-intercept
 * plan (docs/player/youtube-playback.md), which routes media through `http://127.0.0.1:<port>`. A raw
 * POSIX-socket HTTP server binds + listens + accepts on 127.0.0.1 (no entitlement needed for loopback),
 * and [NSURLSession] — the same networking stack WKWebView uses — fetches from it. If the round-trip
 * returns our bytes, an in-app loopback proxy is viable on iOS. Needs no WKWebView/UIApplication, so it
 * runs as an iOS-simulator unit test.
 *
 * `tools/mini.sh gradle :player:iosSimulatorArm64Test --tests '*LoopbackServer*'` (also runs on macosX64).
 */
@OptIn(ExperimentalForeignApi::class)
class LoopbackServerExperimentTest {

    @Test
    fun ios_can_bind_listen_and_serve_over_loopback() {
        val server = LoopbackServer("hello from ios loopback")
        server.start()
        println("[loopback] listening on 127.0.0.1:${server.port}")

        val done = CompletableDeferred<String>()
        val url = NSURL(string = "http://127.0.0.1:${server.port}/hello")!!
        val task = NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, error ->
            val msg = when {
                error != null -> "ERR:${error.localizedDescription}"
                data != null -> "OK:" + (NSString.create(data, NSUTF8StringEncoding)?.toString() ?: "")
                else -> "EMPTY"
            }
            if (!done.isCompleted) done.complete(msg)
        }
        task.resume()

        val start = NSDate()
        while (!done.isCompleted) {
            NSRunLoop.mainRunLoop().runMode(NSDefaultRunLoopMode, beforeDate = NSDate().dateByAddingTimeInterval(0.02))
            if (NSDate().timeIntervalSinceDate(start) > 15.0) break
        }
        server.stop()

        val message = if (done.isCompleted) done.getCompleted() else "TIMEOUT (server never served)"
        println("[loopback] result=$message")
        assertEquals("OK:hello from ios loopback", message, "an iOS process should serve HTTP on 127.0.0.1")
    }
}

/** A minimal blocking HTTP/1.1 server on 127.0.0.1:0 (ephemeral). Any request → [body]. */
@OptIn(ExperimentalForeignApi::class)
private class LoopbackServer(private val body: String) {
    private var listenFd: Int = -1
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var port: Int = 0
        private set

    fun start() {
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            check(fd >= 0) { "socket() failed" }
            listenFd = fd
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            // 127.0.0.1 in network byte order (bytes 7F 00 00 01) — a constant, since ntohs/inet_addr are
            // C macros K/N doesn't expose. All Apple targets are little-endian, so that word is 0x0100007F.
            addr.sin_addr.s_addr = 0x0100007FU
            addr.sin_port = 0u // ephemeral; read back with getsockname
            check(bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
            check(listen(fd, 8) == 0) { "listen() failed" }

            val bound = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(fd, bound.ptr.reinterpret<sockaddr>(), len.ptr)
            port = swap16(bound.sin_port) // network (big-endian) → host
        }
        scope.launch { acceptLoop() }
    }

    /** 16-bit byte swap (ntohs/htons equivalent on a little-endian host). */
    private fun swap16(v: UShort): Int {
        val i = v.toInt() and 0xFFFF
        return ((i and 0xFF) shl 8) or ((i shr 8) and 0xFF)
    }

    private fun acceptLoop() {
        val payload = body.encodeToByteArray()
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            ).encodeToByteArray() + payload
        while (true) {
            val client = accept(listenFd, null, null)
            if (client < 0) break
            val buf = ByteArray(2048)
            buf.usePinned { recv(client, it.addressOf(0), buf.size.convert(), 0) } // drain the request line/headers
            response.usePinned { send(client, it.addressOf(0), response.size.convert(), 0) }
            close(client)
        }
    }

    fun stop() {
        if (listenFd >= 0) close(listenFd)
        scope.cancel()
    }
}
