package io.ymusic.player.webview

import io.ymusic.ytmedia.proxy.ProxyResponse
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.cef.callback.CefCallback
import org.cef.misc.IntRef
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JCEF delivery adapter: the process coroutine prebuffers the body into a 128KB ready buffer
 * (parking while full), CEF reads drain it, and an empty-buffer read parks on the read callback
 * until the next chunk or EOF lands. All tests drive the handler's coroutines on the test
 * scheduler via `backgroundScope`, so no real waiting is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxyResourceHandlerTest {

    private class FakeCallback : CefCallback {
        var continued = 0
        var cancelled = 0
        override fun Continue() { continued++ }
        override fun cancel() { cancelled++ }
    }

    /** Serves [content] in slices of at most [slice] bytes; records pulled bytes and close(). */
    private class FakeSource(content: ByteArray, private val slice: Int = Int.MAX_VALUE) : AsyncSource {
        private val content = content.copyOf()
        private var offset = 0
        var pulled = 0L; private set
        var closed = false; private set

        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (offset >= content.size) return -1L
            val n = minOf(byteCount, slice.toLong(), (content.size - offset).toLong()).toInt()
            sink.write(content, offset, n)
            offset += n
            pulled += n
            return n.toLong()
        }

        override fun close() { closed = true }
    }

    /** First read parks on [gate] — simulates a network stall; close() unblocks a parked read. */
    private class GatedSource(
        private val content: ByteArray,
        private val gate: CompletableDeferred<Unit>,
    ) : AsyncSource {
        private var offset = 0
        var closed = false; private set

        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            gate.await()
            if (closed || offset >= content.size) return -1L
            val n = minOf(byteCount, (content.size - offset).toLong()).toInt()
            sink.write(content, offset, n)
            offset += n
            return n.toLong()
        }

        override fun close() {
            closed = true
            gate.complete(Unit)
        }
    }

    private fun respond(body: AsyncSource, size: Int) = ProxyResponse(
        status = 200,
        reason = "OK",
        headers = listOf("Content-Type" to "application/octet-stream", "Content-Length" to "$size"),
        body = body,
        contentLength = size.toLong(),
        url = "https://r1.googlevideo.com/videoplayback?itag=251",
    )

    @Test
    fun prebuffers_ahead_and_streams_to_eof() = runTest {
        val content = ByteArray(200_000) { (it * 31 and 0xFF).toByte() }
        val source = FakeSource(content, slice = 7_000)
        val handler = ProxyResourceHandler({ respond(source, content.size) }, "/ymusicForward?x", backgroundScope)

        val reqCb = FakeCallback()
        assertTrue(handler.processRequest(null, reqCb))
        runCurrent()
        assertEquals(1, reqCb.continued) // headers resolved

        // CEF reads 8KB at a time; the buffer was prebuffered ahead, so every read is served
        // synchronously — the read callback is never needed.
        val out = Buffer()
        val readCb = FakeCallback()
        val data = ByteArray(8_192)
        val bytesRead = IntRef()
        while (true) {
            if (!handler.readResponse(data, data.size, bytesRead, readCb)) break
            assertTrue(bytesRead.get() > 0, "read must be served from the prebuffered ready buffer")
            out.write(data, 0, bytesRead.get())
            runCurrent() // let the prebuffer loop top the buffer back up
        }
        assertEquals(0, readCb.continued)
        assertContentEquals(content, out.readByteArray())
        assertTrue(source.closed) // upstream released at EOF
    }

    @Test
    fun parks_at_the_ready_cap_until_drained() = runTest {
        val source = FakeSource(ByteArray(1 shl 20), slice = 16 * 1024) // 1MB, way over the 128KB cap
        val handler = ProxyResourceHandler({ respond(source, 1 shl 20) }, "/ymusicForward?x", backgroundScope)

        handler.processRequest(null, FakeCallback())
        runCurrent()
        assertEquals(128L * 1024, source.pulled) // filled to the cap in 8KB chunks, then parked

        val bytesRead = IntRef()
        assertTrue(handler.readResponse(ByteArray(8_192), 8_192, bytesRead, FakeCallback()))
        assertEquals(8_192, bytesRead.get())
        runCurrent()
        // one drain freed space: one more chunk pulled, buffer back at the cap, parked again
        assertEquals(128L * 1024 + 8 * 1024, source.pulled)

        handler.cancel() // release the parked producer before the test scope ends
    }

    @Test
    fun read_waits_via_callback_while_the_buffer_is_empty() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(ByteArray(4_096) { 0x11 }, gate)
        val handler = ProxyResourceHandler({ respond(source, 4_096) }, "/ymusicForward?x", backgroundScope)

        val reqCb = FakeCallback()
        handler.processRequest(null, reqCb)
        runCurrent()
        assertEquals(1, reqCb.continued) // headers are out even though the body is stalled

        val readCb = FakeCallback()
        val bytesRead = IntRef()
        assertTrue(handler.readResponse(ByteArray(8_192), 8_192, bytesRead, readCb))
        assertEquals(0, bytesRead.get()) // nothing buffered yet: CEF told to wait
        assertEquals(0, readCb.continued)

        gate.complete(Unit) // the first chunk lands
        runCurrent()
        assertEquals(1, readCb.continued) // the parked read is woken

        assertTrue(handler.readResponse(ByteArray(8_192), 8_192, bytesRead, readCb))
        assertEquals(4_096, bytesRead.get())
        handler.cancel()
    }

    @Test
    fun cancel_wakes_a_pending_read_and_releases_the_body() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(ByteArray(4_096), gate)
        val handler = ProxyResourceHandler({ respond(source, 4_096) }, "/ymusicForward?x", backgroundScope)

        handler.processRequest(null, FakeCallback())
        runCurrent()

        val readCb = FakeCallback()
        val bytesRead = IntRef()
        assertTrue(handler.readResponse(ByteArray(8_192), 8_192, bytesRead, readCb))
        assertEquals(0, bytesRead.get())

        handler.cancel()
        assertEquals(1, readCb.cancelled) // the parked read is aborted (not a 0-byte clean EOF), not left hanging
        assertEquals(0, readCb.continued)
        runCurrent()
        assertTrue(source.closed) // upstream released
    }

    @Test
    fun serve_failure_cancels_the_request() = runTest {
        val handler = ProxyResourceHandler(
            { throw IOException("boom") },
            "/ymusicForward?x",
            backgroundScope,
        )
        val reqCb = FakeCallback()
        handler.processRequest(null, reqCb)
        runCurrent()
        // serve threw: the request is aborted, not Continued with a (silently-truncated) 502 body.
        assertEquals(1, reqCb.cancelled)
        assertEquals(0, reqCb.continued)
    }
}
