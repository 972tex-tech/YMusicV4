package kmphttp.json

import kmphttp.AsyncSource
import kmphttp.BufferedAsyncSource
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

fun String.toJsonReader(): JsonReader {
    val bytes = encodeToByteArray()
    val source = object : AsyncSource {
        var totalRead = 0
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (totalRead == bytes.size) return -1L
            val maxRead = minOf(bytes.size - totalRead, byteCount.toInt())
            sink.write(bytes, totalRead, maxRead)
            totalRead += maxRead
            return maxRead.toLong()
        }

        override fun close() = Unit
    }
    return JsonReader(BufferedAsyncSource(source))
}