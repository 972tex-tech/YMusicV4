package io.ymusic.dynamicengine

import kmpcommon.io.AsyncSource
import kmphttp.MediaType
import kmphttp.RequestBody
import kmphttp.ResponseBody
import kotlinx.coroutines.awaitCancellation
import okio.Buffer
import okio.IOException

actual fun fakeResponseBody(
    chunks: List<ByteArray>,
    hangAfterChunks: Boolean,
    failAfterChunks: Boolean,
    flag: ClosedFlag,
): ResponseBody =
    object : ResponseBody() {
        private val src = object : AsyncSource {
            private var i = 0
            private val staged = Buffer()

            override suspend fun read(sink: Buffer, byteCount: Long): Long {
                if (staged.size == 0L) {
                    when {
                        i < chunks.size -> staged.write(chunks[i++])
                        failAfterChunks -> throw IOException("origin died")
                        hangAfterChunks -> awaitCancellation()
                        else -> return -1L
                    }
                }
                return staged.read(sink, byteCount)
            }

            override fun close() {
                flag.closed = true
            }
        }

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun asyncSource(): AsyncSource = src
    }

actual suspend fun RequestBody.readAll(): ByteArray {
    val buffer = Buffer()
    val src = openRead()
    try {
        while (src.read(buffer, 8192L) != -1L) { /* accumulate to EOF */ }
    } finally {
        src.close()
    }
    return buffer.readByteArray()
}

actual fun throwingCookieJar(): kmphttp.CookieJar = object : kmphttp.CookieJar {
    override suspend fun saveFromResponse(url: kmphttp.HttpUrl, cookies: List<kmphttp.Cookie>): Unit =
        throw RuntimeException("cookie jar unavailable")

    override suspend fun loadForRequest(url: kmphttp.HttpUrl): List<kmphttp.Cookie> =
        throw RuntimeException("cookie jar unavailable")
}

actual fun wedgedCookieJar(): kmphttp.CookieJar = object : kmphttp.CookieJar {
    override suspend fun saveFromResponse(url: kmphttp.HttpUrl, cookies: List<kmphttp.Cookie>): Unit =
        kotlinx.coroutines.awaitCancellation()

    override suspend fun loadForRequest(url: kmphttp.HttpUrl): List<kmphttp.Cookie> =
        kotlinx.coroutines.awaitCancellation()
}
