package io.ymusic.dynamicengine

import kmphttp.RequestBody
import kmphttp.ResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch

actual fun fakeResponseBody(
    chunks: List<ByteArray>,
    hangAfterChunks: Boolean,
    failAfterChunks: Boolean,
    flag: ClosedFlag,
): ResponseBody {
    val source = object : Source {
        private var i = 0
        private val staged = Buffer()
        private val latch = CountDownLatch(1)
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (closed) throw IOException("closed")
            if (staged.size == 0L) {
                when {
                    i < chunks.size -> staged.write(chunks[i++])
                    failAfterChunks -> throw IOException("origin died")
                    // interruptible: the async bridge (runIoInterruptible) converts a coroutine
                    // cancel into an interrupt, and close() releases it too
                    hangAfterChunks -> {
                        latch.await()
                        throw IOException("closed")
                    }
                    else -> return -1L
                }
            }
            return staged.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
            flag.closed = true
            latch.countDown()
        }
    }
    return source.buffer().asResponseBody(null, -1L)
}

actual suspend fun RequestBody.readAll(): ByteArray = withContext(Dispatchers.IO) {
    // off the engine thread: a streamed body's writeTo runBlocking-pulls pipe reads that must
    // dispatch onto the engine thread — pulling from it would deadlock
    val buffer = Buffer()
    writeTo(buffer)
    buffer.readByteArray()
}

actual fun throwingCookieJar(): kmphttp.CookieJar = object : kmphttp.CookieJar {
    override fun saveFromResponse(url: kmphttp.HttpUrl, cookies: List<kmphttp.Cookie>): Unit =
        throw RuntimeException("cookie jar unavailable")

    override fun loadForRequest(url: kmphttp.HttpUrl): List<kmphttp.Cookie> =
        throw RuntimeException("cookie jar unavailable")
}

actual fun wedgedCookieJar(): kmphttp.CookieJar = object : kmphttp.CookieJar {
    override fun saveFromResponse(url: kmphttp.HttpUrl, cookies: List<kmphttp.Cookie>): Unit = block()

    override fun loadForRequest(url: kmphttp.HttpUrl): List<kmphttp.Cookie> = block()

    // The real wedge is a BLOCKED thread, not a suspension — park the (daemon) IO worker for good.
    private fun <T> block(): T {
        java.util.concurrent.CountDownLatch(1).await()
        throw IllegalStateException("unreachable")
    }
}
