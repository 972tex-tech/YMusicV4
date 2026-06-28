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

    /** Scope tied to this process; cancelled by [terminate]. Use it to drive side-channel I/O. */
    val scope: CoroutineScope

    /** The child's process id. */
    val pid: Long

    /** Send `SIGTERM` and cancel [scope]. */
    fun terminate()
}
