package io.ymusic.quickjs

import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kmpcommon.io.BufferedAsyncSource
import okio.Buffer

/**
 * Read this end through the [AsyncSource] API (wrap in `BufferedAsyncSource` for
 * `readUtf8LineStrict`, `indexOf`, `readDecimalLong`, …). Pure delegation — [PipeSource.receive]
 * already speaks okio (segment moves for buffered bytes, rendezvous into a reserved tail segment
 * when parked).
 *
 * The view is single-reader (like [PipeSource.receive] itself) and does not own the end: `close()`
 * is a no-op; end the stream with [PipeSource.close] (or let the peer's END/RESET surface as EOF /
 * [PipeResetException]).
 */
fun PipeSource.asAsyncSource(): AsyncSource = PipeAsyncSource(this)

/**
 * Read this end through a [BufferedAsyncSource] — okio `BufferedSource`-style buffered reads
 * (`readLong`, `readInt`, `readUtf8LineStrict`, `readDecimalLong`, …) over [PipeSource.receive].
 * A view built on [asAsyncSource]; same lifecycle (does not own the end — close it with
 * [PipeSource.close]).
 */
fun PipeSource.asBufferedSource(): BufferedAsyncSource = BufferedAsyncSource(asAsyncSource())

/**
 * Write to this end through the [AsyncSink] API. Pure delegation — [PipeSink.send] already speaks
 * okio (each segment crosses the FFI pinned, zero-copy, consumed only after the peer accepted it).
 *
 * The view is single-writer and does not own the end: `flush()`/`close()` are no-ops; close the
 * end with [PipeSink.close] (END or RESET) when done.
 */
fun PipeSink.asAsyncSink(): AsyncSink = PipeAsyncSink(this)

private class PipeAsyncSource(private val source: PipeSource) : AsyncSource {
    override suspend fun read(sink: Buffer, byteCount: Long): Long = source.receive(sink, byteCount)

    override fun close() = Unit // a view — the pipe's read end is closed via PipeSource.close()
}

private class PipeAsyncSink(private val sink: PipeSink) : AsyncSink {
    override suspend fun write(buffer: Buffer, byteCount: Long) = sink.send(buffer, byteCount)

    override suspend fun flush() = Unit // send() delivers immediately; nothing is staged here

    override fun close() = Unit // a view — close the pipe's write end via PipeSink.close(reason)
}
