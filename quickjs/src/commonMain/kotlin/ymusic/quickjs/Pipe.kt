package ymusic.quickjs

/**
 * A bidirectional **byte stream** between Kotlin and JS, modeled on a pair of Linux pipes (a
 * socketpair): each direction is an independent, **flow-controlled** byte channel with a bounded
 * buffer (64 KB). One side opens a pipe with a text [command] (routing name only); the other
 * accepts it.
 *
 * Backpressure is intrinsic, exactly like `write(2)`/`read(2)` on a kernel pipe:
 *  - [send] **suspends** while the peer's receive buffer is full, and resumes as the peer drains it.
 *    The bytes cross the boundary with at most one buffered copy (see PIPE_STREAMING.md); the buffer
 *    must stay unmodified until the call returns, after which it is free to reuse. It throws
 *    [BrokenPipeException] (EPIPE) once the peer has closed its read end ([closeRead]).
 *  - [receive] **suspends** while the buffer is empty. It returns `-1` at EOF (the peer [close]d its
 *    write end cleanly and you drained what it sent) and throws [PipeResetException] if the peer
 *    closed with a RESET reason.
 *
 * Two closes, mirroring `shutdown(SHUT_WR)` vs closing the read end:
 *  - [close] is a write-side half-close. With `reason == null` it is a clean END: the peer's
 *    [receive] returns `-1` once drained. A non-null [reason] is a **RESET** (mid-stream failure,
 *    cancellation): the peer's pending data is discarded and its parked/next [receive] throws
 *    [PipeResetException] carrying the reason. Either way you can still [receive] until the peer
 *    closes too.
 *  - [closeRead] abandons the read side: the peer's next [send] throws [BrokenPipeException]. Use it
 *    to tell a producer "stop, I'm not reading anymore."
 */
interface Pipe {
    /** The command name this pipe was opened with. */
    val command: String

    /**
     * Deliver `buffer[offset, offset+len)` to the other side, suspending while its buffer is full.
     * The range must stay unmodified until this returns (partial writes hold a window into it).
     * Throws [BrokenPipeException] if the peer closed its read end.
     */
    suspend fun send(buffer: ByteArray, offset: Int, len: Int)

    /** Convenience: send a whole array. */
    suspend fun send(bytes: ByteArray) = send(bytes, 0, bytes.size)

    /** Read up to [len] bytes into [buffer] at [offset]; returns bytes read (≤ len), or -1 at EOF.
     *  Throws [PipeResetException] if the peer RESET the pipe. */
    suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int

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
