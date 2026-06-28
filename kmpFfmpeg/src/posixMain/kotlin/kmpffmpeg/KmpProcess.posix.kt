package kmpffmpeg

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import platform.posix.EINTR
import platform.posix.SIGTERM
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.O_WRONLY
import platform.posix.errno
import platform.posix.execve
import platform.posix.fork
import platform.posix.POLLIN
import platform.posix.kill
import platform.posix.open
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.strerror
import platform.posix.waitpid
import platform.posix.write

/**
 * Desktop-native backend (macOS + Linux) over `fork` + `execve` (macOS's `platform.posix` does not
 * bind `posix_spawn`). The child's stdin/stdout/stderr are wired to pipes and exposed as okio
 * streams on the returned [KmpProcess]; the exit code is harvested by a background `waitpid` poll.
 */
internal actual fun startProcess(builder: KmpProcessBuilder): KmpProcess = spawn(builder)

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun spawn(builder: KmpProcessBuilder): KmpProcess = memScoped {
    // stdin is always a pipe (parent writes inPipe[1]). stdout/stderr each get a pipe (captured, we
    // read [0]) or are pointed at /dev/null (discarded → the child writes freely, nothing to drain).
    val inPipe = allocArray<IntVar>(2)
    check(pipe(inPipe) == 0) { "pipe() failed: ${errnoMessage()}" }
    val inRead = inPipe[0]; val inWrite = inPipe[1]

    val outPipe = if (!builder.isStdoutDiscarded) {
        allocArray<IntVar>(2).also { check(pipe(it) == 0) { "pipe() failed: ${errnoMessage()}" } }
    } else null
    val errPipe = if (!builder.isStderrDiscarded) {
        allocArray<IntVar>(2).also { check(pipe(it) == 0) { "pipe() failed: ${errnoMessage()}" } }
    } else null

    // One /dev/null shared by whichever of stdout/stderr is discarded.
    val devNull = if (outPipe == null || errPipe == null) {
        open("/dev/null", O_WRONLY).also { check(it >= 0) { "open(/dev/null) failed: ${errnoMessage()}" } }
    } else -1
    val childOut = outPipe?.get(1) ?: devNull
    val childErr = errPipe?.get(1) ?: devNull

    // Build argv / envp as C arrays before fork (the child must not allocate). The child runs with
    // exactly `environment` set — no parent-env inheritance (ffmpeg launched by absolute path needs
    // none; callers pass e.g. LD_LIBRARY_PATH on Linux explicitly).
    val args = builder.arguments
    val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
    argv[0] = builder.binaryPath.cstr.getPointer(this)
    args.forEachIndexed { i, a -> argv[i + 1] = a.cstr.getPointer(this) }
    argv[args.size + 1] = null

    val envEntries = builder.environment.map { (k, v) -> "$k=$v" }
    val envp = allocArray<CPointerVar<ByteVar>>(envEntries.size + 1)
    envEntries.forEachIndexed { i, e -> envp[i] = e.cstr.getPointer(this) }
    envp[envEntries.size] = null

    val pid = fork()
    check(pid >= 0) { "fork() failed: ${errnoMessage()}" }
    if (pid == 0) {
        // Child: only async-signal-safe calls past this point (no Kotlin allocation).
        dup2(inRead, 0)
        dup2(childOut, 1)
        dup2(childErr, 2)
        close(inRead); close(inWrite)
        if (outPipe != null) { close(outPipe[0]); close(outPipe[1]) }
        if (errPipe != null) { close(errPipe[0]); close(errPipe[1]) }
        if (devNull >= 0) close(devNull)
        execve(builder.binaryPath, argv, envp)
        _exit(127) // exec failed
    }

    // Parent: close the child's ends; keep the ends it drives.
    close(inRead)
    if (outPipe != null) close(outPipe[1])
    if (errPipe != null) close(errPipe[1])
    if (devNull >= 0) close(devNull)

    PosixKmpProcess(
        processId = pid,
        stdin = FdSink(inWrite),
        // A discarded stream has no read end — surface an empty (immediately-EOF) source.
        stdout = if (outPipe != null) FdSource(outPipe[0]) else Buffer(),
        stderr = if (errPipe != null) FdSource(errPipe[0]) else Buffer(),
    )
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class PosixKmpProcess(
    private val processId: Int,
    override val stdin: Sink,
    override val stdout: Source,
    override val stderr: Source,
) : KmpProcess {
    override val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    override val pid: Long = processId.toLong()

    override val exitCode: Deferred<Int> = scope.async {
        // Non-blocking reap so we never tie up a worker thread waiting on the child.
        while (true) {
            val (reaped, status) = memScoped {
                val s = alloc<IntVar>()
                waitpid(processId, s.ptr, WNOHANG) to s.value
            }
            if (reaped == processId) return@async exitCodeOf(status)
            if (reaped < 0) {
                if (errno == EINTR) continue
                return@async -1
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    override fun terminate() {
        kill(processId, SIGTERM)
        scope.cancel()
    }
}

/**
 * okio [Source] over a raw fd, zero-copy: grow the sink Buffer by [byteCount], walk the newly-added
 * segments with the cursor reading fd bytes straight into each, then shrink back to the bytes actually
 * read. A short read (fd drained) stops the walk so we never leave a gap mid-buffer.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class FdSource(private val fd: Int) : Source {
    private var eof = false
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        if (eof) return -1L
        val oldSize = sink.size
        val cursor = Buffer.UnsafeCursor()
        sink.readAndWriteUnsafe(cursor)
        var total = 0L
        try {
            cursor.resizeBuffer(oldSize + byteCount) // 1) allocate the request (a chain of segments)
            while (total < byteCount) {              // 2) read the fd into each segment in turn
                // The first read blocks (we want to wait for output / EOF). Once we already hold bytes,
                // only keep going if the fd has more ready *now* — never block while sitting on data.
                if (total > 0L && !readable(fd)) break
                val data = checkNotNull(cursor.data)
                val capacity = cursor.end - cursor.start
                val n = data.usePinned { pinned ->
                    var r: Long
                    while (true) {
                        r = read(fd, pinned.addressOf(cursor.start), capacity.convert()).toLong()
                        if (r < 0L && errno == EINTR) continue
                        break
                    }
                    r
                }
                if (n < 0L) throw IOException("read(fd=$fd) failed: ${errnoMessage()}")
                if (n == 0L) {
                    eof = true
                    break
                }
                total += n
                if (n < capacity) break          // fd drained — stop before the next segment
                if (total < byteCount) {
                    check(cursor.next() != -1)
                }
            }
        } finally {
            if (total < byteCount) {
                cursor.resizeBuffer(oldSize + total)     // 3) shrink to what we actually read (also on throw)
            }
            cursor.close()
        }
        return if (total == 0L) {
            check(eof)
            -1L
        } else {
            total
        }
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() { close(fd) }
}

/**
 * okio [Sink] over a raw fd. Writes the source Buffer's segments straight to the fd via an
 * [Buffer.UnsafeCursor] — no intermediate ByteArray — handling partial writes per segment.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class FdSink(private val fd: Int) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        if (byteCount == 0L) return
        var remaining = byteCount
        val cursor = Buffer.UnsafeCursor()
        source.readUnsafe(cursor)
        try {
            while (remaining > 0L) {
                check(cursor.next() != -1)
                val data = checkNotNull(cursor.data)
                val toWrite = minOf((cursor.end - cursor.start).toLong(), remaining)
                data.usePinned { pinned ->
                    var off = 0L
                    while (off < toWrite) {
                        val w = write(fd, pinned.addressOf(cursor.start + off.toInt()), (toWrite - off).convert()).toLong()
                        if (w < 0L) {
                            if (errno == EINTR) continue
                            throw IOException("write(fd=$fd) failed: ${errnoMessage()}")
                        }
                        off += w
                    }
                }
                remaining -= toWrite
            }
        } finally {
            cursor.close()
            source.skip(byteCount - remaining) // consume what we wrote (the cursor is read-only)
        }
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() { close(fd) }
}

/** Non-blocking probe: true if [fd] has data — or EOF — ready to read right now (a zero-timeout poll). */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun readable(fd: Int): Boolean = memScoped {
    val pfd = alloc<pollfd>()
    pfd.fd = fd
    pfd.events = POLLIN.convert()
    while (true) {
        val r = poll(pfd.ptr, 1.convert(), 0) // timeout 0 → returns immediately
        if (r < 0 && errno == EINTR) continue
        return@memScoped r > 0
    }
    @Suppress("UNREACHABLE_CODE") false
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun errnoMessage(): String = strerror(errno)?.toKString() ?: "errno $errno"

/** WIFEXITED ? WEXITSTATUS : -1, computed from the conventional wait-status layout. */
private fun exitCodeOf(status: Int): Int =
    if ((status and 0x7f) == 0) (status shr 8) and 0xff else -1
