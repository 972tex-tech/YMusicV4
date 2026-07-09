package kmphttp.json

import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import okio.Buffer

fun ByteArray.toJsonReader(): JsonReader {
    val bytes = this
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

fun String.toJsonReader(): JsonReader = encodeToByteArray().toJsonReader()

/** Like [toJsonReader] but every read returns at most [chunk] bytes — forces refill boundaries. */
fun String.toJsonReaderChunked(chunk: Int): JsonReader {
    val bytes = encodeToByteArray()
    val source = object : AsyncSource {
        var totalRead = 0
        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            if (totalRead == bytes.size) return -1L
            val maxRead = minOf(bytes.size - totalRead, byteCount.toInt(), chunk)
            sink.write(bytes, totalRead, maxRead)
            totalRead += maxRead
            return maxRead.toLong()
        }

        override fun close() = Unit
    }
    return JsonReader(BufferedAsyncSource(source))
}
