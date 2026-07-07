package ymusic.quickjs

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import okio.Buffer

/**
 * Read this pipe through the [AsyncSource] API (wrap in `BufferedAsyncSource` for `readUtf8LineStrict`,
 * `indexOf`, `readDecimalLong`, …). Pure delegation — [Pipe.receive] already speaks okio (segment
 * moves for buffered bytes, rendezvous into a reserved tail segment when parked).
 *
 * The view is single-reader (like [Pipe.receive] itself) and does not own the pipe: `close()` is a
 * no-op; end the stream with [Pipe.closeRead] (or let the peer's END/RESET surface as EOF /
 * [PipeResetException]).
 */
fun Pipe.asAsyncSource(): AsyncSource = PipeAsyncSource(this)

/**
 * Read this pipe through a [BufferedAsyncSource] — okio `BufferedSource`-style buffered reads
 * (`readLong`, `readInt`, `readUtf8LineStrict`, `readDecimalLong`, …) over [receive]. A view built
 * on [asAsyncSource]; same lifecycle (does not own the pipe — end it with [Pipe.closeRead]).
 */
fun Pipe.asBufferedSource(): BufferedAsyncSource = BufferedAsyncSource(asAsyncSource())

/**
 * Write to this pipe through the [AsyncSink] API. Pure delegation — [Pipe.send] already speaks okio
 * (each segment crosses the FFI pinned, zero-copy, consumed only after the peer accepted it).
 *
 * The view is single-writer and does not own the pipe: `flush()`/`close()` are no-ops; half-close
 * with [Pipe.close] (END or RESET) when done.
 */
fun Pipe.asAsyncSink(): AsyncSink = PipeAsyncSink(this)

private class PipeAsyncSource(private val pipe: Pipe) : AsyncSource {
    override suspend fun read(sink: Buffer, byteCount: Long): Long = pipe.receive(sink, byteCount)

    override fun close() = Unit // a view — the pipe's read end is closed via Pipe.closeRead()
}

private class PipeAsyncSink(private val pipe: Pipe) : AsyncSink {
    override suspend fun write(buffer: Buffer, byteCount: Long) = pipe.send(buffer, byteCount)

    override suspend fun flush() = Unit // send() delivers immediately; nothing is staged here

    override fun close() = Unit // a view — half-close the pipe via Pipe.close(reason)
}
