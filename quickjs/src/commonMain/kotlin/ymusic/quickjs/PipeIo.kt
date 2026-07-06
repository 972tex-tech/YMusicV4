package ymusic.quickjs

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import okio.Buffer
import okio.use

/**
 * Read this pipe through the [AsyncSource] API (wrap in `BufferedAsyncSource` for `readUtf8LineStrict`,
 * `indexOf`, `readDecimalLong`, …). Bytes land straight in okio segment arrays: the reserved tail
 * segment is the destination [Pipe.receive] parks with, so the pipe's rendezvous path copies JS's
 * bytes directly into the buffer — one copy, no scratch array.
 *
 * The view is single-reader (like [Pipe.receive] itself) and does not own the pipe: `close()` is a
 * no-op; end the stream with [Pipe.closeRead] (or let the peer's END/RESET surface as EOF /
 * [PipeResetException]).
 */
fun Pipe.asAsyncSource(): AsyncSource = PipeAsyncSource(this)

/**
 * Write to this pipe through the [AsyncSink] API. Each okio segment of the buffer is handed to
 * [Pipe.send] as-is (the segment array crosses the FFI pinned, zero-copy) and consumed only after
 * the send fully completes, so backpressure suspends here with nothing lost.
 *
 * The view is single-writer and does not own the pipe: `flush()`/`close()` are no-ops; half-close
 * with [Pipe.close] (END or RESET) when done.
 */
fun Pipe.asAsyncSink(): AsyncSink = PipeAsyncSink(this)

private const val SEGMENT_SIZE = 8192L // okio Segment.SIZE (not public in commonMain)

private class PipeAsyncSource(private val pipe: Pipe) : AsyncSource {
    private val cursor = Buffer.UnsafeCursor()

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        val oldSize = sink.size
        // Reserve tail capacity and capture its segment window; the cursor is closed again before
        // suspending so the buffer is never left with a cursor attached across the receive.
        var dst: ByteArray? = null
        var dstOff = 0
        var want = 0
        sink.readAndWriteUnsafe(cursor).use { c ->
            c.resizeBuffer(oldSize + minOf(byteCount, SEGMENT_SIZE))
            dst = c.data
            dstOff = c.start
            want = minOf(byteCount, (c.end - c.start).toLong()).toInt()
        }
        var n = -1
        try {
            n = pipe.receive(dst!!, dstOff, want)
        } finally {
            // Trim the unfilled reservation (all of it on EOF or an exception).
            sink.readAndWriteUnsafe(cursor).use { c -> c.resizeBuffer(oldSize + maxOf(n, 0)) }
        }
        return n.toLong()
    }

    override fun close() = Unit // a view — the pipe's read end is closed via Pipe.closeRead()
}

private class PipeAsyncSink(private val pipe: Pipe) : AsyncSink {
    private val cursor = Buffer.UnsafeCursor()

    override suspend fun write(buffer: Buffer, byteCount: Long) {
        require(byteCount in 0..buffer.size) { "byteCount $byteCount out of range (size ${buffer.size})" }
        var remaining = byteCount
        while (remaining > 0L) {
            var data: ByteArray? = null
            var start = 0
            var n = 0
            buffer.readUnsafe(cursor).use { c ->
                c.seek(0L)
                data = c.data
                start = c.start
                n = minOf(remaining, (c.end - c.start).toLong()).toInt()
            }
            pipe.send(data!!, start, n) // suspends under backpressure; the segment stays put meanwhile
            buffer.skip(n.toLong()) // consume only after the send fully completed
            remaining -= n
        }
    }

    override suspend fun flush() = Unit // send() delivers immediately; nothing is staged here

    override fun close() = Unit // a view — half-close the pipe via Pipe.close(reason)
}
