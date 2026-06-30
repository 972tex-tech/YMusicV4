package kmpffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import okio.Sink
import okio.Source

/** A running child process: its standard streams, exit code, and lifecycle. */
interface KmpProcess {
    /**
     * Writes to the child's stdin. If the child closes stdin before you finish writing, the write
     * fails with `EPIPE` — surfaced as an `IOException` — and on a default signal disposition also
     * raises `SIGPIPE`, which terminates the process. The host should ignore `SIGPIPE` once at
     * startup; this module never touches the signal disposition.
     */
    val stdin: Sink

    /** The child's stdout (empty if [KmpProcessBuilder.discardStdout] was set). Drain it, then close it. */
    val stdout: Source

    /** The child's stderr (empty if [KmpProcessBuilder.discardStderr] was set). Drain it, then close it. */
    val stderr: Source

    /** Completes with the child's exit code once it terminates. */
    val exitCode: Deferred<Int>

    /** Scope tied to this process; cancelled by [close]. Use it to drive side-channel I/O. */
    val scope: CoroutineScope

    /** The child's process id. */
    val pid: Long

    /**
     * The one lifecycle method, idempotent and non-blocking: if the child is still running (its
     * [exitCode] hasn't completed) send `SIGTERM`/`destroy`, then cancel [scope] and close
     * stdin/stdout/stderr. Fire-and-forget — it does NOT wait for the child to exit or for side-channel
     * coroutines to finish. Releasing the fds is mandatory: nothing else closes them, and on native they
     * have no finalizer, so an un-closed process leaks fds.
     *
     * Killing the child also makes a blocked read on its stdout/stderr return, so running such a read on
     * [scope] and then calling [close] is how you unblock a stuck pipe read (e.g. on a timeout).
     *
     * Drain/serve any side-channel endpoint (e.g. an `FfOutput` that must flush) *before* calling this,
     * since cancelling [scope] stops their coroutines.
     */
    fun close(error: Throwable? = null)
}
