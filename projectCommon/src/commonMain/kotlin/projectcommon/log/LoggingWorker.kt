@file:OptIn(ExperimentalTime::class)

package projectcommon.log

import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmpcommon.nowMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Bounded in-memory log queue plus a single-file-writer coroutine. [enqueue] timestamps on the
 * caller and [trySend]s — never blocks, never throws (engine-thread safe). The worker formats
 * each field into an okio sink, then rotates by size.
 *
 * On disk: [fileName], [fileName].1, … up to [keepFiles] files. Newest is unsuffixed; highest
 * suffix is oldest. A rotate shifts `.(n-1)` → `.n` and the live file to `.1`.
 */
class LoggingWorker(
    directory: Path,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val fileName: String = "ymusic.log",
    private val maxFileBytes: Long = 5L * 1024 * 1024,
    private val keepFiles: Int = 3,
    queueCapacity: Int = 100,
) {
    private val dir = directory
    private val current = dir / fileName
    private val lines = Channel<Line>(queueCapacity, BufferOverflow.DROP_OLDEST)
    private val commands = Channel<Flush>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var sink: BufferedSink? = null
    private var written = 0L
    private var dead = false

    init {
        require(keepFiles >= 1)
        require(maxFileBytes > 0L)
        scope.launch {
            try {
                for (line in lines) writeLine(line)
            } finally {
                closeSink()
            }
        }
        scope.launch {
            for (cmd in commands) {
                runCatching { sink?.flush() }
                cmd.done?.complete(Unit)
            }
        }
    }

    /** Clock + enqueue. Safe on any thread, including the QuickJS engine thread. */
    fun enqueue(level: Logger.Level, path: String, message: String, error: Throwable?) {
        try {
            lines.trySend(Line(Clock.System.nowMs(), level, path, message, error))
        } catch (_: Throwable) {
        }
    }

    /** Best-effort: wake the worker to flush the live sink. Does not wait. */
    fun requestFlush() {
        commands.trySend(Flush(null))
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(Flush(done))
        done.await()
    }

    /** Live + rotated paths that currently exist, oldest first. Stat-only; no flush. */
    fun filePaths(): List<String> {
        val out = ArrayList<String>(keepFiles)
        for (i in keepFiles - 1 downTo 0) {
            val p = fileAt(i)
            if (fs.exists(p)) out += p.toString()
        }
        return out
    }

    fun close() {
        scope.cancel()
    }

    private fun writeLine(e: Line) {
        if (dead) return
        try {
            val s = openSink()
            val buf = s.buffer
            val start = buf.size
            writeTimestamp(buf, e.ts)
            buf.writeByte(' '.code)
            buf.writeUtf8(e.level.name)
            if (e.path.isNotEmpty()) {
                buf.writeByte(' '.code)
                buf.writeUtf8(e.path)
            }
            buf.writeUtf8(": ")
            buf.writeUtf8(e.message)
            val err = e.error
            if (err != null) {
                buf.writeByte('\n'.code)
                val stack = err.stackTraceToString()
                if (stack.length > STACK_CAP) buf.writeUtf8(stack, 0, STACK_CAP) else buf.writeUtf8(stack)
            }
            buf.writeByte('\n'.code)
            written += buf.size - start
            s.flush()
            if (written >= maxFileBytes) rotate()
        } catch (_: Throwable) {
            dead = true
            closeSink()
        }
    }

    private fun openSink(): BufferedSink {
        sink?.let { return it }
        fs.createDirectories(dir)
        written = if (fs.exists(current)) fs.metadata(current).size ?: 0L else 0L
        if (written >= maxFileBytes) rotate()
        val s = fs.appendingSink(current).buffer()
        sink = s
        return s
    }

    private fun rotate() {
        closeSink()
        val last = fileAt(keepFiles - 1)
        if (fs.exists(last)) fs.delete(last, mustExist = false)
        for (i in keepFiles - 2 downTo 1) {
            val from = fileAt(i)
            if (fs.exists(from)) fs.atomicMove(from, fileAt(i + 1))
        }
        if (keepFiles > 1 && fs.exists(current)) fs.atomicMove(current, fileAt(1))
        written = 0L
    }

    private fun fileAt(index: Int): Path = if (index == 0) current else dir / "$fileName.$index"

    private fun closeSink() {
        val s = sink ?: return
        sink = null
        try {
            s.flush()
        } catch (_: Throwable) {
        }
        try {
            s.close()
        } catch (_: Throwable) {
        }
    }

    private class Line(
        val ts: Long,
        val level: Logger.Level,
        val path: String,
        val message: String,
        val error: Throwable?,
    )

    private class Flush(val done: CompletableDeferred<Unit>?)

    private companion object {
        const val STACK_CAP = 2000

        fun writeTimestamp(buf: Buffer, epochMs: Long) {
            val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
            buf.writePad2(t.hour)
            buf.writeByte('.'.code)
            buf.writePad2(t.minute)
            buf.writeByte('.'.code)
            buf.writePad2(t.second)
            buf.writeByte('.'.code)
            buf.writePad3(t.nanosecond / 1_000_000)
        }

        fun Buffer.writePad2(n: Int) {
            writeByte('0'.code + n / 10)
            writeByte('0'.code + n % 10)
        }

        fun Buffer.writePad3(n: Int) {
            writeByte('0'.code + n / 100)
            writeByte('0'.code + (n / 10) % 10)
            writeByte('0'.code + n % 10)
        }
    }
}

/** [CrumbLogger] sink that hands every line to [worker]. */
class FileLogger private constructor(
    private val worker: LoggingWorker,
    crumbs: List<Crumb>,
) : CrumbLogger(crumbs) {
    constructor(worker: LoggingWorker) : this(worker, emptyList())

    override fun fork(crumbs: List<Crumb>): CrumbLogger = FileLogger(worker, crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        worker.enqueue(level, path, message, error)
    }
}
