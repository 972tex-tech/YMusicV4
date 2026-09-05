package io.ymusic.ytmedia.proxy

import kmpcommon.io.AsyncSource
import kmpcommon.io.readByteArray
import kmphttp.MediaType
import kmphttp.MediaTypeX.toMediaTypeOrNull
import kmphttp.RequestBody
import kmphttp.ResponseBody

internal actual suspend fun readRequestBody(body: RequestBody): ByteArray =
    body.openRead().readByteArray()

internal actual fun proxyResponseBody(
    source: AsyncSource,
    headers: List<Pair<String, String>>,
    contentLength: Long?,
): ResponseBody {
    val mediaType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        ?.second
        ?.toMediaTypeOrNull()
    val len = contentLength ?: -1L
    return object : ResponseBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = len
        override fun asyncSource(): AsyncSource = source
    }
}
