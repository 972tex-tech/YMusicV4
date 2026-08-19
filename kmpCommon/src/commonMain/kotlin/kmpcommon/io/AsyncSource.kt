package kmpcommon.io

import kmpcommon.kilobytes
import okio.Buffer
import okio.BufferedSink
import okio.Closeable
import okio.EOFException
import okio.Sink
import okio.use

interface AsyncSource : Closeable {
    /**
     * This function may perform either blocking or non-blocking read, so the caller should not be
     * main thread.
     *
     * @return the number of bytes read, or -1 if this source is ended.
     */
    suspend fun read(sink: Buffer, byteCount: Long): Long

    companion object {
        val EMPTY = object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long = -1
            override fun close() = Unit
        }
    }
}

class BufferedAsyncSource(private val delegate: AsyncSource) : AsyncSource {
    val buffer = Buffer()
    var closed: Boolean = false
        private set

    private var eof = false

    suspend fun exhausted(): Boolean {
        check(!closed) { "closed" }
        if (buffer.size > 0) return false
        if (eof) return true
        if (delegate.read(buffer, 16.kilobytes) == -1L) {
            eof = true
            return true
        }
        return false
    }

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        if (buffer.size > 0) {
            return buffer.read(sink, minOf(buffer.size, byteCount))
        }
        if (eof) return -1L
        val count = delegate.read(sink, byteCount)
        eof = count == -1L
        return count
    }

    suspend fun request(byteCount: Long): Boolean {
        check(byteCount >= 0)
        check(!closed) { "closed" }
        if (buffer.size >= byteCount) return true
        if (eof) return false
        do {
            val readCount = delegate.read(buffer, 16.kilobytes)
            if (readCount == -1L) {
                eof = true
                return false
            }
        } while (buffer.size < byteCount)
        return true
    }

    suspend fun require(byteCount: Long) {
        if (!request(byteCount)) throw EOFException("Not enough $byteCount bytes")
    }

    suspend fun readFully(): Buffer {
        check(!closed) { "closed" }
        var readCount: Long
        do {
            readCount = delegate.read(buffer, 16.kilobytes)
        } while (readCount > -1)
        eof = true
        return buffer
    }

    /**
     * @see [okio.Buffer.indexOf]
     */
    suspend fun indexOf(byte: Byte): Long {
        return indexOf(byte, 0)
    }

    /**
     * @see [okio.Buffer.indexOf]
     */
    suspend fun indexOf(byte: Byte, fromIndex: Long): Long {
        return indexOf(byte, fromIndex, Long.MAX_VALUE)
    }

    /**
     * @see [okio.Buffer.indexOf]
     */
    suspend fun indexOf(byte: Byte, fromIndex: Long, toIndex: Long): Long {
        check(fromIndex <= toIndex)
        check(!closed) { "closed" }
        if (fromIndex == toIndex) return -1L
        if (!request(fromIndex + 1)) return -1L
        var index = buffer.indexOf(byte, fromIndex, toIndex)
        while (index == -1L && buffer.size < toIndex) {
            val oldSize = buffer.size
            if (!request(oldSize + 1)) return -1L // EOF already
            index = buffer.indexOf(byte, oldSize, toIndex)
        }
        return index
    }

    suspend fun readUtf8LineStrict(): String {
        check(!closed) { "closed" }
        val lineBreakChar = '\n'.code.toByte()
        val lineBreakIndex = this.indexOf(lineBreakChar)
        if (lineBreakIndex == -1L) {
            throw EOFException("\n not found in source")
        }
        if (lineBreakIndex > 0 && buffer[lineBreakIndex - 1] == '\r'.code.toByte()) {
            val line = buffer.readUtf8(lineBreakIndex - 1)
            buffer.skip(2L)
            return line
        } else {
            val line = buffer.readUtf8(lineBreakIndex)
            buffer.skip(1L)
            return line
        }
    }

    suspend fun readDecimalLong(): Long {
        require(1)

        var pos = 0L
        while (request(pos + 1)) {
            val b = buffer[pos]
            if ((b < '0'.code.toByte() || b > '9'.code.toByte()) && (pos != 0L || b != '-'.code.toByte())) {
                // Non-digit, or non-leading negative sign.
                if (pos == 0L) {
                    throw NumberFormatException("Expected a digit or '-' but was 0x${b.toString(16)}")
                }
                break
            }
            pos++
        }

        return buffer.readDecimalLong()
    }

    /** Fixed-width big-endian reads (okio `BufferedSource.readByte/readInt/readLong`), blocking until
     *  the full 1/4/8 bytes arrive. Throws [EOFException] if the source ends first. */
    suspend fun readByte(): Byte {
        require(1)
        return buffer.readByte()
    }

    suspend fun readInt(): Int {
        require(4)
        return buffer.readInt()
    }

    suspend fun readLong(): Long {
        require(8)
        return buffer.readLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
        buffer.clear()
    }
}

fun AsyncSource.buffer(): BufferedAsyncSource = BufferedAsyncSource(this)

suspend fun BufferedSink.writeAll(source: AsyncSource): Long {
    var totalRead = 0L
    while (true) {
        // Okio segment SIZE
        // const val SIZE = 8192
        val readCount = source.read(this.buffer, 8192)
        if (readCount == -1L) break
        totalRead += readCount
        // Push complete segments downstream as we go (okio's RealBufferedSink.writeAll does the
        // same). Without this the whole source accumulates in the sink's buffer until the caller
        // flushes — streaming a large upload holds the entire payload in RAM (OOM on mobile),
        // despite the streaming name.
        emitCompleteSegments()
    }
    return totalRead
}

/** Pump this buffered source fully into [sink] (okio's `BufferedSource.readAll` shape), returning the
 *  byte count. The source-side twin of [BufferedSink.writeAll]; neither end is closed. */
suspend fun BufferedAsyncSource.readAll(sink: AsyncSink): Long {
    var total = 0L
    while (!exhausted()) {
        val n = buffer.size
        sink.write(buffer, n)
        total += n
    }
    return total
}

suspend fun BufferedAsyncSource.readAll(sink: Sink): Long {
    var total = 0L
    while (!exhausted()) {
        val n = buffer.size
        sink.write(buffer, n)
        total += n
    }
    return total
}

suspend fun AsyncSource.readUtf8String(): String {
    return BufferedAsyncSource(this).use { source ->
        source.readFully().readUtf8()
    }
}

suspend fun AsyncSource.readByteArray(): ByteArray {
    return BufferedAsyncSource(this).use { source ->
        source.readFully().readByteArray()
    }
}
