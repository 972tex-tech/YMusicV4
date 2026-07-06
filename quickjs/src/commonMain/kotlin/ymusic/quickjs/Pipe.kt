package ymusic.quickjs

import okio.Buffer

/**
 * A bidirectional **byte stream** between Kotlin and JS, modeled on a pair of Linux pipes (a
 * socketpair): each direction is an independent, **flow-controlled** byte channel with a bounded
 * buffer (64 KB). One side opens a pipe with a text [command] (routing name only); the other
 * accepts it.
 *
 * Backpressure is intrinsic, exactly like `write(2)`/`read(2)` on a kernel pipe:
 *  - [send] **suspends** while the peer's receive buffer is full, and resumes as the peer drains it.
 *    Each okio segment crosses the FFI as-is (the segment array is pinned for the call, zero-copy)
 *    and is consumed only after the peer fully accepted it, so nothing is lost while parked. It
 *    throws [BrokenPipeException] (EPIPE) once the peer has closed its read end ([closeRead]).
 *  - [receive] **suspends** while the buffer is empty. Already-buffered bytes move into the sink as
 *    whole okio segments (no copy); when parked, the writer's bytes are copied straight into the
 *    sink's segments at delivery time (one copy, no scratch array). It returns `-1` at EOF (the
 *    peer [close]d its write end cleanly and you drained what it sent) and throws
 *    [PipeResetException] if the peer closed with a RESET reason.
 *
 * For the buffered reading/writing APIs (`readUtf8LineStrict`, `readDecimalLong`, …) adapt with
 * [asAsyncSource]/[asAsyncSink] — those are views: closing them does not close the pipe.
 *
 * Two closes, mirroring `shutdown(SHUT_WR)` vs closing the read end:
 *  - [close] is a write-side half-close. With `reason == null` it is a clean END: the peer's
 *    [receive] returns `-1` once drained. A non-null [reason] is a **RESET** (mid-stream failure,
 *    cancellation): the peer's pending data is discarded and its parked/next [receive] throws
 *    [PipeResetException] carrying the reason. Either way you can still [receive] until the peer
 *    closes too.
 *  - [closeRead] abandons the read side: the peer's next [send] throws [BrokenPipeException]. Use it
 *    to tell a producer "stop, I'm not reading anymore."
 *
 * **Aborts close the pipe.** A [send] or [receive] that exits abnormally — cancellation included —
 * leaves the stream at an unknowable position (bytes partially transferred, or delivered into an
 * abandoned sink), so it closes that side on the way out: an aborted send RESETs (the peer's read
 * throws `"send aborted mid-stream: …"`), an aborted receive closes the read end (the peer's send
 * gets EPIPE). One predictable state — a pipe that threw is done on that side, never half-usable.
 */
interface Pipe {
    /** The command name this pipe was opened with. */
    val command: String

    /**
     * Send exactly [byteCount] bytes of [source], consuming them (okio `Sink.write` semantics),
     * suspending while the peer's buffer is full. Each okio segment crosses the FFI as-is (the
     * segment array is pinned for the call, zero-copy) and is consumed only after the peer fully
     * accepted it, so backpressure suspends here with nothing lost. [source] must not be touched
     * until this returns. Throws [BrokenPipeException] if the peer closed its read end.
     */
    suspend fun send(source: Buffer, byteCount: Long)

    /**
     * Read up to [byteCount] bytes into [sink]; returns bytes read, or -1 at EOF (okio `Source.read`
     * semantics). Already-buffered bytes move into [sink] as whole okio segments (no copy); when the
     * pipe is empty this parks with [sink] itself, and the writer's bytes are copied straight into
     * its segments at delivery time (one copy, no scratch array). [sink] must not be touched until
     * this returns. Throws [PipeResetException] if the peer RESET the pipe.
     */
    suspend fun receive(sink: Buffer, byteCount: Long): Long

    /**
     * Write-side half-close: END when [reason] is null (the peer's [receive] returns -1 once
     * drained), RESET otherwise (the peer's [receive] throws [PipeResetException]). Afterwards
     * [send] is a misuse and throws [IllegalStateException] (you've declared yourself done writing).
     */
    suspend fun close(reason: String? = null)

    /**
     * Abandon the read side: the peer's next [send] throws [BrokenPipeException] (EPIPE). Afterwards
     * [receive] is a misuse and throws [IllegalStateException] (you've declared yourself done reading).
     */
    suspend fun closeRead()
}

/** Thrown by [Pipe.send] when the peer has closed its read end — the Linux `EPIPE` / `SIGPIPE`. */
class BrokenPipeException(message: String = "broken pipe") : RuntimeException(message)

/** Thrown by [Pipe.receive] when the peer closed the pipe with a RESET [reason] (mid-stream
 *  failure or cancellation) rather than a clean END. */
class PipeResetException(val reason: String) : RuntimeException(reason)
