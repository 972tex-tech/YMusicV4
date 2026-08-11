package io.ymusic.quickjs

import okio.Buffer

/**
 * A bidirectional **byte stream** between Kotlin and JS, modeled on a pair of Linux pipes (a
 * socketpair): each direction is an independent, **flow-controlled** byte channel with a bounded
 * buffer (64 KB). One side opens a pipe with a text [command] (routing name only); the other
 * accepts it. Like `pipe(2)` hands back two fds, a [Pipe] is just the pair of ends this side
 * holds: the [source] you read, the [sink] you write — each closed independently, and closing
 * both releases the pipe. There is no fused close.
 *
 * Backpressure is intrinsic, exactly like `write(2)`/`read(2)` on a kernel pipe:
 *  - [PipeSink.send] **suspends** while the peer's receive buffer is full, and resumes as the peer
 *    drains it. Each okio segment crosses the FFI as-is (the segment array is pinned for the call,
 *    zero-copy) and is consumed only after the peer fully accepted it, so nothing is lost while
 *    parked. It throws [BrokenPipeException] (EPIPE) once the peer has closed its source.
 *  - [PipeSource.receive] **suspends** while the buffer is empty. Already-buffered bytes move into
 *    the sink as whole okio segments (no copy); when parked, the writer's bytes are copied straight
 *    into the sink's segments at delivery time (one copy, no scratch array). It returns `-1` at EOF
 *    (the peer closed its sink cleanly and you drained what it sent) and throws
 *    [PipeResetException] if the peer closed with a RESET reason.
 *
 * For the buffered reading/writing APIs (`readUtf8LineStrict`, `readDecimalLong`, …) adapt with
 * [asAsyncSource]/[asAsyncSink] — those are views: closing them does not close the pipe end.
 *
 * **Aborts close the end.** A send or receive that exits abnormally — cancellation included —
 * leaves the stream at an unknowable position (bytes partially transferred, or delivered into an
 * abandoned sink), so it closes that end on the way out: an aborted send RESETs (the peer's read
 * throws `"send aborted mid-stream: …"`), an aborted receive closes the source (the peer's send
 * gets EPIPE). One predictable state — an end that threw is done, never half-usable.
 */
class Pipe internal constructor(
    /** The readable end: bytes the peer writes. */
    val source: PipeSource,
    /** The writable end: bytes toward the peer. */
    val sink: PipeSink,
    /** The command name this pipe was opened with. */
    val command: String,
) {
    operator fun component1(): PipeSource = source

    operator fun component2(): PipeSink = sink
}

/** The readable end of a [Pipe]. */
interface PipeSource {
    /**
     * Read up to [byteCount] bytes into [sink]; returns bytes read, or -1 at EOF (okio `Source.read`
     * semantics). Already-buffered bytes move into [sink] as whole okio segments (no copy); when the
     * pipe is empty this parks with [sink] itself, and the writer's bytes are copied straight into
     * its segments at delivery time (one copy, no scratch array). [sink] must not be touched until
     * this returns. Throws [PipeResetException] if the peer RESET the pipe.
     */
    suspend fun receive(sink: Buffer, byteCount: Long): Long

    /**
     * Close the readable end (`close(2)` on the read fd): the peer's next send throws
     * [BrokenPipeException] (EPIPE). Afterwards [receive] is a misuse and throws
     * [IllegalStateException] (you've declared yourself done reading). Idempotent.
     */
    suspend fun close()
}

/** The writable end of a [Pipe]. */
interface PipeSink {
    /**
     * Send exactly [byteCount] bytes of [source], consuming them (okio `Sink.write` semantics),
     * suspending while the peer's buffer is full. Each okio segment crosses the FFI as-is (the
     * segment array is pinned for the call, zero-copy) and is consumed only after the peer fully
     * accepted it, so backpressure suspends here with nothing lost. [source] must not be touched
     * until this returns. Throws [BrokenPipeException] if the peer closed its source; throws
     * [SendClosedException] if this write side was closed (before the call, or by a concurrent
     * [close] while the send was parked at the cap).
     */
    suspend fun send(source: Buffer, byteCount: Long)

    /**
     * Close the writable end: END when [reason] is null (the peer's receive returns -1 once
     * drained), RESET otherwise (the peer's pending data is discarded and its parked/next receive
     * throws [PipeResetException] carrying the reason — mid-stream failure, cancellation).
     * Afterwards [send] is a misuse and throws [SendClosedException] (you've declared yourself done
     * writing). A [close] that lands while a send is parked at the cap fails that send the same
     * way. Idempotent.
     */
    suspend fun close(reason: String? = null)
}

/** Thrown by [PipeSink.send] when the peer has closed its source — the Linux `EPIPE` / `SIGPIPE`. A
 *  PEER-caused condition, distinct from [SendClosedException] (this side closed its own write). */
class BrokenPipeException(message: String = "broken pipe") : RuntimeException(message)

/** Thrown by [PipeSink.send] when THIS side's write end is closed: the send was issued after
 *  [PipeSink.close], or a concurrent [PipeSink.close] aborted it while it was parked at the cap. A
 *  self-caused misuse (hence an [IllegalStateException]), distinct from the peer-caused
 *  [BrokenPipeException] (EPIPE). */
class SendClosedException(message: String) : IllegalStateException(message)

/** Thrown by [PipeSource.receive] when the peer closed the pipe with a RESET [reason] (mid-stream
 *  failure or cancellation) rather than a clean END. */
class PipeResetException(val reason: String) : RuntimeException(reason)
