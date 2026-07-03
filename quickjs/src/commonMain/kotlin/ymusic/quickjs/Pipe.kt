package ymusic.quickjs

/**
 * A bidirectional **byte stream** between Kotlin and JS — socket-style. One side opens a pipe with a
 * text [command] (routing name only); the other accepts it. Then both stream raw bytes until [close].
 *
 * Ownership: [send] is copy-first — it snapshots `buffer[offset, offset+len)` into the wire frame
 * before returning, so after you await it the buffer is free to reuse. Never mutate a buffer while an
 * un-awaited [send] on it is in flight. [receive] copies inbox bytes into your buffer and returns the
 * count; read it after the call returns.
 */
interface Pipe {
    /** The command name this pipe was opened with. */
    val command: String

    /** Copy `buffer[offset, offset+len)` to the other side. Consumes the range before returning. */
    suspend fun send(buffer: ByteArray, offset: Int, len: Int)

    /** Convenience: send a whole array. */
    suspend fun send(bytes: ByteArray) = send(bytes, 0, bytes.size)

    /** Read up to [len] bytes into [buffer] at [offset]; returns bytes read (≤ len), or -1 at EOF. */
    suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int

    /** Signal end-of-stream to the other side (its [receive] returns -1 once drained). */
    suspend fun close()
}
