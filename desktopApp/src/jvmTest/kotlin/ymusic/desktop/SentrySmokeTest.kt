package ymusic.desktop

import io.sentry.kotlin.multiplatform.Sentry
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/** An UNCAUGHT exception must reach Sentry with no capture call of our own — the whole point of
 *  the integration; guards the default-on uncaught-handler across SDK bumps. */
class SentrySmokeTest {

    @Test
    fun uncaughtExceptionIsAutoCaptured() {
        val received = CountDownLatch(1)
        val firstLine = StringBuilder()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                firstLine.append(input.readLine())
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                socket.getOutputStream().flush()
                received.countDown()
            }
        }

        Sentry.init {
            it.dsn = "http://publickey@127.0.0.1:${server.localPort}/1"
        }

        val crasher = thread(name = "crasher") {
            throw IllegalStateException("sentry smoke: deliberate uncaught crash")
        }
        crasher.join()

        assertTrue(received.await(20, TimeUnit.SECONDS), "no envelope arrived at the fake Sentry server")
        assertTrue(firstLine.contains("/api/1/envelope"), "unexpected request: $firstLine")
        Sentry.close()
    }
}
