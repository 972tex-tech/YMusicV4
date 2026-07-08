package kmpffmpeg

import android.content.ContentResolver
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kmpffmpeg.FfYmixProtocolServer.Companion.MAX_CHUNK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InterruptedIOException

/**
 * The app side of ffmpeg's `ymix` proxy protocol (libavformat/ymix.c), serving ONE file — typically a
 * `content://` uri ffmpeg can't open itself — over ONE abstract-namespace unix socket. ffmpeg connects
 * as the client; [url] is the `-i`/output spec.
 *
 * `ymix` is pull-based and strictly synchronous: ffmpeg opens ONE connection per `url_open()` and sends
 * one request, waiting for exactly one reply, before the next. There is no session id, no control/data
 * split, and no concurrency on a connection — so each connection is a plain blocking loop, and a
 * connection IS a session. A concurrent open of the same socket — mp4 `+faststart` re-opens its output
 * read-only to relocate the moov atom to the front — is simply ANOTHER independent connection with its
 * own file descriptor; there is no session-id routing and nothing to cross-wire.
 *
 * Wire protocol (`\n`-terminated ASCII control lines; in-band binary payloads):
 * ```
 * FF -> start_session read | start_session write     (first line)
 *    <- ok | error <code>
 * FF -> read  <pos> <len>          <- chunk <n>\n<n bytes>   (n <= len; n == 0 means EOF, not a close)
 * FF -> write <pos> <len>\n<bytes> <- ok                     (per-write acknowledgement)
 * FF -> stat                       <- stat_ok <size>
 * FF -> bye                        <- ok                     (graceful close; the write is already on the fd)
 * ```
 * An error reply is `error <code>` where `<code>` is a stable [YmixError] token (never free-form text),
 * so ffmpeg's client can map it to an AVERROR.
 * read/write chunks are capped at 32 KiB (= ffmpeg's AVIO buffer). A `write` session opens the document
 * `"rwt"` (read/write + truncate at open, matching the `file:` protocol); a `read` session opens `"r"`.
 * [FileOutputStream] is unbuffered, so a write hits the fd immediately and a concurrent read connection
 * (faststart) sees it without an explicit flush.
 *
 * Lifecycle: [start] BEFORE launching ffmpeg (it connects on start); [close] after the process ends.
 */
internal class FfYmixProtocolServer(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val socketName: String = nextName(),
) {
    /** ffmpeg input/output spec. Leading `0` selects the abstract namespace (no filesystem entry). */
    val url: String get() = "ymix:0$socketName"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: LocalServerSocket? = null

    // The ffmpeg process this server proxies for, set via [onProcessStarted]. The server runs on its own
    // orphan scope, so a handler failure has nowhere to propagate structurally; we surface it by failing
    // the process — [KmpProcess.close] completes its `exitCode` exceptionally with the cause, so the app
    // awaiting `exitCode` learns WHY the proxy failed (a typed Throwable), not just that ffmpeg exited.
    @Volatile
    private var ffProcess: KmpProcess? = null

    @Volatile
    private var closed = false

    /** Hand the server the started process so a fatal proxy error can fail it with the real cause. */
    fun onProcessStarted(process: KmpProcess) {
        ffProcess = process
    }

    /**
     * Bind and start accepting. The [LocalServerSocket] constructor binds + listens *synchronously*, so
     * the socket is ready the instant this returns — the kernel backlogs ffmpeg's connection even before
     * the accept loop coroutine runs, so there is no readiness gap for the caller to wait on.
     */
    fun start() {
        val srv = LocalServerSocket(socketName) // synchronous bind + listen
        server = srv
        scope.launch {
            while (isActive) {
                val sock = try {
                    runInterruptible { srv.accept() }
                } catch (t: Throwable) {
                    // close() shut the listen fd down (expected), or accept genuinely failed. Either way
                    // stop accepting. A genuine failure (not teardown) is surfaced by failing the process
                    // with the cause. NEVER rethrow: an uncaught throw from this launched coroutine would
                    // crash the whole app.
                    if (!closed) ffProcess?.close(t)
                    break
                }
                launch { handleConnection(sock) }
            }
        }
    }

    /**
     * One connection = one session. Handshake, then a blocking request/reply loop. All blocking socket
     * and file I/O runs inside a single [runInterruptible] so [close]'s `scope.cancel()` interrupts it.
     */
    private suspend fun handleConnection(sock: LocalSocket) = runInterruptible {
        var pfd: ParcelFileDescriptor? = null
        try {
            val source = sock.inputStream.source().buffer()
            val sink = sock.outputStream.sink().buffer()
            val hello = source.readUtf8LineStrict().split(' ')
            val mode = hello.getOrNull(1)
            if (hello.firstOrNull() != "start_session" || (mode != "read" && mode != "write")) {
                sink.replyError(YmixError.BAD_HANDSHAKE)
                return@runInterruptible // ffmpeg's protocol error
            }
            // Opening the document is the response to `start_session`: on failure reply the classified
            // code (and fail the process) right here, at the reply boundary. "rwt" = read/write + truncate
            // at open (the file: default); "r" = read-only.
            pfd = try {
                contentResolver.openFileDescriptor(uri, if (mode == "read") "r" else "rwt")
                    ?: throw IOException("ContentResolver: Couldn't open $uri")
            } catch (t: Throwable) {
                rethrowIfInterrupted(t)
                sink.failCommand(t)
                return@runInterruptible
            }
            sink.writeUtf8("ok\n").flush()
            if (mode == "read") {
                serveRead(source, sink, pfd)
            } else {
                serveWrite(source, sink, pfd)
            }
        } catch (t: Throwable) {
            rethrowIfInterrupted(t)
            // We only reach here from a SOCKET read/write failure (ffmpeg disconnected) or an unexpected
            // error — NEVER at a command-reply boundary. An `error` reply is a RESPONSE; writing one here
            // could inject it mid-stream (e.g. after a partial `chunk`), so we don't. Per-command failures
            // already replied via [failCommand]; here we only surface a genuine failure to the app by
            // failing the process. Never rethrow — an uncaught throw from this launched coroutine would
            // crash the app.
            if (!closed) ffProcess?.close(t)
        } finally {
            runCatching { pfd?.close() } // owns the fd; the FileIn/OutputStream over it is not the owner
            runCatching { sock.close() }
        }
    }

    /**
     * Read session: serve `read`/`stat`/`bye`. We track the fd's position and only `lseek` when ffmpeg
     * actually seeks (it reads mostly sequentially, so this skips a syscall per chunk). The stream is
     * closed via [use] (on Android it isn't the fd owner, so the fd is still released by `pfd.close()`).
     */
    private fun serveRead(source: BufferedSource, sink: BufferedSink, pfd: ParcelFileDescriptor) {
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val channel = fis.channel
            val buf = ByteArray(MAX_CHUNK)
            var currentPos = 0L // the fd starts at offset 0
            while (true) {
                val parts = (source.readUtf8Line() ?: return).split(' ') // null = peer closed
                when (parts.firstOrNull()) {
                    "read" -> {
                        val pos = parts.getOrNull(1)?.toLongOrNull()
                        val len = parts.getOrNull(2)?.toIntOrNull()
                        if (pos == null || len == null || pos < 0 || len < 0) {
                            sink.replyError(YmixError.BAD_REQUEST); return
                        }
                        var sent: Int
                        // Do the file read BEFORE writing any reply byte, so a failure replies a clean
                        // `error` (never after a partial `chunk`). The chunk write below is a socket op —
                        // its failure means ffmpeg is gone, and propagates (no `error` reply).
                        try {
                            if (pos != currentPos) {
                                channel.position(pos); currentPos = pos
                            } // seek only on a real move
                            val n =
                                fis.read(buf, 0, minOf(len, MAX_CHUNK)) // -1 at EOF, else 1..len
                            sent = if (n < 0) 0 else n
                            currentPos += sent
                        } catch (t: Throwable) {
                            rethrowIfInterrupted(t)
                            sink.failCommand(t)
                            return
                        }
                        sink.writeUtf8("chunk $sent\n")
                        if (sent > 0) sink.write(buf, 0, sent)
                        sink.flush()
                    }

                    "stat" -> {
                        val size = try {
                            channel.size()
                        } catch (t: Throwable) {
                            rethrowIfInterrupted(t)
                            sink.failCommand(t)
                            return
                        }
                        sink.writeUtf8("stat_ok $size\n").flush()
                    }

                    "bye" -> {
                        sink.writeUtf8("ok\n").flush()
                        return
                    }

                    else -> {
                        sink.replyError(YmixError.UNKNOWN_COMMAND)
                        return
                    }
                }
            }
        }
    }

    private fun rethrowIfInterrupted(error: Throwable) {
        if (error is InterruptedIOException) throw InterruptedException().initCause(error)
        if (error is InterruptedException) throw error
    }

    /**
     * Write session: serve `write`/`stat`/`bye`, overwriting at any offset (via seek). The payload is
     * streamed through a fixed [MAX_CHUNK] buffer — never read whole into memory — and the position is
     * tracked to skip redundant seeks. [FileOutputStream] is unbuffered, so each write hits the fd at
     * once (a concurrent faststart read connection sees it). `bye` just acknowledges and closes.
     */
    private fun serveWrite(source: BufferedSource, sink: BufferedSink, pfd: ParcelFileDescriptor) {
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            val channel = fos.channel
            val buf = ByteArray(MAX_CHUNK)
            var currentPos = 0L
            while (true) {
                val parts = (source.readUtf8Line() ?: return).split(' ')
                when (parts.firstOrNull()) {
                    "write" -> {
                        val pos = parts.getOrNull(1)?.toLongOrNull()
                        val len = parts.getOrNull(2)?.toIntOrNull()
                        if (pos == null || len == null || pos < 0 || len < 0) {
                            sink.replyError(YmixError.BAD_REQUEST)
                            return
                        }
                        try {
                            if (pos != currentPos) {
                                channel.position(pos)
                                currentPos = pos
                            }
                            var remaining = len
                            while (remaining > 0) {
                                // Reading the payload is a SOCKET op: a short/closed read = ffmpeg gone → propagate.
                                val n = source.read(buf, 0, minOf(remaining, MAX_CHUNK))
                                if (n < 0) throw EOFException("ymix: peer closed mid-write ($remaining of $len left)")
                                // The file write is the failure-prone part → reply `error` on failure.
                                fos.write(buf, 0, n)
                                remaining -= n
                                currentPos += n
                            }
                            fos.flush()
                        } catch (t: Throwable) {
                            rethrowIfInterrupted(t)
                            sink.failCommand(t)
                            return
                        }
                        sink.writeUtf8("ok\n").flush()
                    }

                    "stat" -> {
                        val size = try {
                            channel.size()
                        } catch (t: Throwable) {
                            rethrowIfInterrupted(t)
                            sink.failCommand(t)
                            return
                        }
                        sink.writeUtf8("stat_ok $size\n").flush()
                    }

                    "bye" -> {
                        sink.writeUtf8("ok\n").flush()
                        return
                    }

                    else -> {
                        sink.replyError(YmixError.UNKNOWN_COMMAND)
                        return
                    }
                }
            }
        }
    }

    /** Reply a single `error <code>` response for the current command — ffmpeg's own protocol error
     *  (bad handshake / unknown command / malformed args); not a server failure, so don't fail the process. */
    private fun BufferedSink.replyError(code: YmixError) {
        runCatching { writeUtf8("error ${code.code}\n").flush() }
    }

    /**
     * Report a server-side failure that occurred while ffmpeg awaited a reply: fail the process with the
     * typed cause FIRST (close completes exitCode exceptionally and wins, because ffmpeg is still blocked
     * awaiting this reply — replying first would let ffmpeg exit and complete exitCode normally, racing
     * the cause away, as observed on API 24), then reply the classified `error <code>` (best-effort; a
     * still-listening ffmpeg, or one with no process ref, maps the code).
     */
    private fun BufferedSink.failCommand(t: Throwable) {
        ffProcess?.close(t)
        replyError(YmixError.of(t))
    }

    fun close() {
        closed = true // gate the accept loop / handlers so teardown-time socket errors stay quiet
        ffProcess = null
        // Unblock the blocked accept() (https://issuetracker.google.com/issues/36945762), then cancel —
        // which interrupts any in-flight handler's runInterruptible, closing its pfd + socket.
        runCatching { server?.fileDescriptor?.let { Os.shutdown(it, OsConstants.SHUT_RDWR) } }
        runCatching { server?.close() }
        scope.cancel()
    }

    private companion object {
        const val MAX_CHUNK = 32 * 1024 // ymix.c caps read/write chunks at 32 KiB
        private var counter = 0

        @Synchronized
        private fun nextName() = "kmpffmpeg_ymix_${counter++}"
    }
}

/**
 * Stable error codes the server sends as `error <code>\n`. ffmpeg's `ymix` client switches on these to
 * pick an AVERROR, so they are fixed snake_case ASCII tokens, never free-form text — keep this list in
 * sync with `libavformat/ymix.c`.
 */
private enum class YmixError(val code: String) {
    BAD_HANDSHAKE("bad_handshake"),         // first line wasn't `start_session read|write`
    UNKNOWN_COMMAND("unknown_command"),     // unrecognized command on an open session
    BAD_REQUEST("bad_request"),             // malformed args — missing / non-numeric <pos>/<len>
    FILE_NOT_FOUND("file_not_found"),       // the backing document/uri does not exist
    PERMISSION_DENIED("permission_denied"), // no access (SAF grant revoked, EACCES/EPERM)
    NO_SPACE("no_space"),                   // a write failed because storage is full (ENOSPC)
    IO_ERROR("io_error");                   // any other I/O failure

    companion object {
        /** Classify a thrown failure into a wire code. */
        fun of(t: Throwable): YmixError = when {
            t is SecurityException -> PERMISSION_DENIED
            t is NumberFormatException || t is IndexOutOfBoundsException -> BAD_REQUEST
            t is android.system.ErrnoException -> when (t.errno) {
                OsConstants.ENOENT -> FILE_NOT_FOUND
                OsConstants.EACCES, OsConstants.EPERM -> PERMISSION_DENIED
                OsConstants.ENOSPC -> NO_SPACE
                else -> IO_ERROR
            }
            // Android funnels the underlying errno into FileNotFoundException's message for file: opens.
            t is FileNotFoundException -> t.message.orEmpty().let { m ->
                when {
                    "EACCES" in m || "Permission denied" in m -> PERMISSION_DENIED
                    "ENOSPC" in m -> NO_SPACE
                    else -> FILE_NOT_FOUND
                }
            }
            else -> IO_ERROR
        }
    }
}
