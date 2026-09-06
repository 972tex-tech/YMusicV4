package io.ymusic.ytmedia.proxy

import kmpcommon.io.AsyncSource
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.RequestBody
import kmphttp.ResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

internal actual suspend fun readRequestBody(body: RequestBody): Buffer {
    val buf = Buffer()
    /* writeTo → runBlocking on a oneshot pipe. Must not run on the engine
       thread or JS never finishes writing the SABR POST and execute hangs
       (playground: two sabr=1 logs, no HTTP 200). */
    withContext(Dispatchers.IO) { body.writeTo(buf) }
    return buf
}

internal actual fun proxyResponseBody(
    source: AsyncSource,
    headers: List<Pair<String, String>>,
    contentLength: Long?,
): ResponseBody {
    val mediaType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        ?.second
        ?.toMediaTypeOrNull()
    val len = contentLength ?: -1L
    return object : okhttp3.ResponseBody() {
        override fun contentType() = mediaType
        override fun contentLength() = len
        override fun source(): BufferedSource = AsyncToBlockingSource(source).buffer()
    }
}

private class AsyncToBlockingSource(private val inner: AsyncSource) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long = runBlocking { inner.read(sink, byteCount) }
    override fun close() = inner.close()
    override fun timeout(): Timeout = Timeout.NONE
}
