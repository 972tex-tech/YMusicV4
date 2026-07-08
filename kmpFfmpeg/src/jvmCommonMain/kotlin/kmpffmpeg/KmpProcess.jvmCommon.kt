package kmpffmpeg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.io.InputStream

/** JVM / Android backend over [ProcessBuilder], exposed as a streaming [KmpProcess]. */
internal actual fun startProcess(builder: KmpProcessBuilder): KmpProcess {
    val process = ProcessBuilder(buildList { add(builder.binaryPath); addAll(builder.arguments) })
        .apply {
            // ProcessBuilder.environment() starts as a COPY of the parent environment. The
            // KmpProcessBuilder contract (and the posix backend) is "the child runs with exactly
            // these — no inheritance", so clear it before applying ours.
            environment().clear()
            environment().putAll(builder.environment)
        }
        .start()
    return JvmKmpProcess(process, builder.isStdoutDiscarded, builder.isStderrDiscarded)
}

private class JvmKmpProcess(
    private val process: Process,
    discardStdout: Boolean,
    discardStderr: Boolean,
) : KmpProcess {
    override val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override val stdin: Sink = process.outputStream.sink()
    override val stdout: Source = exposeOrDrain(process.inputStream, discardStdout)
    override val stderr: Source = exposeOrDrain(process.errorStream, discardStderr)
    override val pid: Long = process.obtainPid()
    override val exitCode = CompletableDeferred<Int>()

    init {
        scope.launch {
            val code = runInterruptible { process.waitFor() }
            exitCode.complete(code)
        }
    }

    private var closed = false
    override fun close(error: Throwable?) {
        if (closed) return
        closed = true

        // If the child is still running, fail its exit code with the reason and destroy it (which also
        // unblocks a blocked stdout/stderr read). Mirrors the posix backend.
        if (!exitCode.isCompleted) {
            exitCode.completeExceptionally(error ?: CancellationException("process canceled"))
            process.destroy()
        }

        if (error is CancellationException) {
            scope.cancel(error)
        } else {
            scope.cancel("Process closed", error)
        }
        runCatching { stdin.close() }          // release the parent-side pipe fds
        runCatching { stdout.close() }
        runCatching { stderr.close() }
    }

    /**
     * `discard == false` → hand the stream to the caller. `discard == true` → drain it to nowhere on a
     * background coroutine (so the child never blocks on a full pipe) and give the caller an empty,
     * immediately-EOF source. We can't use `ProcessBuilder.redirectOutput`/`Redirect.DISCARD` — those
     * are API 26/28, above our minSdk 24 — so draining is the API-agnostic equivalent.
     */
    private fun exposeOrDrain(stream: InputStream, discard: Boolean): Source =
        if (!discard) {
            stream.source()
        } else {
            // Close the source inside the coroutine, after draining — closing it here (around the
            // launch) would shut it before the coroutine ever reads it.
            scope.launch {
                try {
                    stream.source().use { blackholeSink().buffer().writeAll(it) }
                } catch (e: IOException) {
                    // The discarded stream was closed/interrupted under us — e.g. close() destroying the
                    // process mid-drain throws InterruptedIOException on the blocked read. Nothing
                    // consumes these bytes, so there's nothing to report. Critically, an uncaught throw
                    // from this fire-and-forget launch reaches the thread's uncaught handler and kills
                    // the whole process (observed crashing the app on API 31).
                }
            }
            Buffer()
        }
}

/**
 * Desktop JDK 9+ has `Process.pid()`, but Android's `java.lang.Process` does not — read the private
 * `pid` field of its impl reflectively instead. Returns -1 if neither is available.
 */
private fun Process.obtainPid(): Long {
    // Resolve pid() on the public Process base (not the package-private impl class, whose method
    // invoke() rejects under JDK strong encapsulation). NoSuchMethod on Android → field fallback.
    runCatching { return Process::class.java.getMethod("pid").invoke(this) as Long }
    runCatching {
        val field = javaClass.getDeclaredField("pid").apply { isAccessible = true }
        return field.getInt(this).toLong()
    }
    return -1L
}
