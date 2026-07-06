package ymusic.quickjs

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okio.Buffer
import kotlin.coroutines.cancellation.CancellationException

/** Per-direction pipe buffer bound (bytes) — like a Linux pipe's fixed capacity. A writer whose
 *  write is only partially accepted parks and resumes on the peer's DRAIN notify, which fires once
 *  the buffer drains to [PIPE_LOW]. Must match bootstrap.js. */
private const val PIPE_CAP = 64 * 1024
private const val PIPE_LOW = PIPE_CAP / 2

private const val EOF = -1

/**
 * A QuickJS engine reached over an in-process pipe protocol, on every KMP target. JS execution is
 * confined to a single thread; the FFI boundary ([NativeEngine]) shuttles operations. On top of it
 * this multiplexes logical [Pipe]s: either side can [openPipe] a command to the other, or [onAccept]
 * commands the other opens.
 *
 * Each pipe direction is a **bounded, flow-controlled byte stream** whose buffer lives on the
 * *reader's* side: bytes JS writes to us land here, in an okio [Buffer] (segment pool, no per-chunk
 * allocation); bytes we write to JS land in its queue of pooled C segments. Writes cross the FFI
 * zero-copy and are **rendezvous-first**: when a reader is parked, the writer's bytes are copied
 * straight into the reader's buffer (one copy) and only the remainder is buffered (a second copy for
 * those bytes, never a throwaway allocation). A partially accepted write parks the writer until the
 * reader drains to the low-water mark (DRAIN). All state lives on the engine thread — no locking.
 */
class QuickJsRuntime private constructor(
    private val engine: NativeEngine,
    private val engineDispatcher: CloseableCoroutineDispatcher,
) {

    private val scope = CoroutineScope(engineDispatcher + SupervisorJob())
    private val pipes = HashMap<Int, PipeImpl>()
    private val accepts = HashMap<String, suspend (Pipe) -> Unit>()
    private var nextId = 1 // Kotlin opens odd ids; JS opens even.

    /**
     * Evaluate [script] synchronously (to load engine code / register JS `ymix.onAccept` handlers).
     * Returns the completion value's string form, or null; throws [QuickJsException] on a JS error.
     */
    suspend fun load(script: String, filename: String = "<load>"): String? =
        withContext(engineDispatcher) { engine.evalSync(script, filename) }

    /** Handle pipes JS opens with the exact [command] name. Any arguments are read inside [handler]
     *  from the pipe's bytes — the command is a routing name only, never carries args. */
    fun onAccept(command: String, handler: suspend (Pipe) -> Unit) {
        accepts[command] = handler
    }

    /** Open a pipe to JS addressed to the [command] name (send any arguments as bytes afterwards).
     *  Fails AT THE OPEN — like ECONNREFUSED — when no JS handler serves [command]. */
    suspend fun openPipe(command: String): Pipe = withContext(engineDispatcher) {
        val id = nextId
        nextId += 2
        val pipe = PipeImpl(id, command)
        // Registered before open() so anything crossing during the call finds the pipe; dropped
        // again on refusal/throw so a failed open never dangles in the registry.
        pipes[id] = pipe
        val rc = try {
            engine.open(id, command)
        } catch (e: Throwable) {
            pipes.remove(id)
            throw e
        }
        if (rc < 0) {
            pipes.remove(id)
            throw QuickJsException("open(\"$command\") refused: no JS handler")
        }
        pipe
    }

    /** JS opened a pipe to us — route to the registered handler (on the engine thread).
     *  Returns 0 (accepted) or -1 (refused: no handler), which fails JS's open() call itself. */
    internal fun onOpen(id: Int, command: String): Int {
        val handler = accepts[command] ?: return -1
        val pipe = PipeImpl(id, command)
        pipes[id] = pipe
        scope.launch {
            try {
                handler(pipe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                pipe.closeRead()
                pipe.close(e.message ?: "handler failed") // RESET: surface the failure to JS
            }
        }
        return 0
    }

    internal fun onWrite(id: Int, chunk: NativeChunk): Int = pipes[id]?.deliver(chunk) ?: -1

    internal fun onCloseWrite(id: Int, reason: String?) { pipes[id]?.onPeerCloseWrite(reason) }

    internal fun onCloseRead(id: Int) { pipes[id]?.onPeerCloseRead() }

    internal fun onDrain(id: Int) { pipes[id]?.onDrain() }

    private inner class PipeImpl(val id: Int, override val command: String) : Pipe {
        // ---- inbound: JS writes, we buffer (okio segment pool) & read ----
        private val inbound = Buffer()
        private val cursor = Buffer.UnsafeCursor()
        private var writeEnded = false      // peer closed its write end -> EOF once drained
        private var resetReason: String? = null // peer RESET -> receive throws
        private var readClosed = false      // we closed our read end
        private var peerBlocked = false     // last deliver() accepted less than offered; we owe a DRAIN
        private var receiving = false
        private var reader: ParkedReader? = null

        // ---- outbound: we write, JS buffers ----
        private var sending = false
        private var drainCredit = false     // DRAIN arrived before we parked (lost-wakeup guard)
        private var peerReadClosed = false  // peer closed its read end -> our send throws EPIPE
        private var weEnded = false
        private var writer: CancellableContinuation<Unit>? = null

        // ---- inbound plumbing (all on the engine thread) ----

        /** JS wrote [chunk] (valid only during this call). Rendezvous-first: fill a parked reader's
         *  buffer directly, then the okio buffer up to [PIPE_CAP]. Returns accepted, or -1 (EPIPE). */
        fun deliver(chunk: NativeChunk): Int {
            if (readClosed) return -1
            var pos = 0
            val r = reader
            if (r != null) {
                reader = null
                val n = minOf(chunk.size, r.len)
                chunk.copyInto(r.dst, r.offset, 0, n)
                pos = n
                r.cont.resumeWith(Result.success(n))
            }
            val room = (PIPE_CAP.toLong() - inbound.size).toInt()
            val toBuffer = minOf(chunk.size - pos, room)
            if (toBuffer > 0) {
                bufferChunk(chunk, pos, toBuffer)
                pos += toBuffer
            }
            if (pos < chunk.size) peerBlocked = true
            return pos
        }

        /** Copy `[srcOffset, srcOffset+count)` of [chunk] straight into okio segment arrays. */
        private fun bufferChunk(chunk: NativeChunk, srcOffset: Int, count: Int) {
            val c = inbound.readAndWriteUnsafe(cursor)
            try {
                val oldSize = inbound.size
                c.resizeBuffer(oldSize + count)
                var copied = 0
                while (copied < count) {
                    c.seek(oldSize + copied)
                    val n = minOf(count - copied, c.end - c.start)
                    chunk.copyInto(c.data!!, c.start, srcOffset + copied, n)
                    copied += n
                }
            } finally {
                c.close()
            }
        }

        fun onPeerCloseWrite(reason: String?) {
            writeEnded = true
            val r = reader
            if (reason != null) {
                resetReason = reason
                inbound.clear() // RESET: the stream is suspect — discard, fail fast
                if (r != null) { reader = null; r.cont.resumeWith(Result.failure(PipeResetException(reason))) }
            } else if (r != null) { // a parked reader implies an empty buffer -> EOF now
                reader = null
                r.cont.resumeWith(Result.success(EOF))
            }
            maybeReap()
        }

        fun onPeerCloseRead() {
            peerReadClosed = true
            val w = writer
            if (w != null) { writer = null; w.resumeWith(Result.failure(BrokenPipeException())) }
        }

        fun onDrain() {
            val w = writer
            if (w != null) { writer = null; w.resumeWith(Result.success(Unit)) }
            else drainCredit = true
        }

        override suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int =
            withContext(engineDispatcher) {
                if (len == 0) return@withContext 0
                check(!readClosed) { "receive() after closeRead()" }
                check(!receiving) { "concurrent receive() on the same pipe" }
                receiving = true
                try {
                    resetReason?.let { throw PipeResetException(it) }
                    if (inbound.size > 0L) {
                        val n = inbound.read(buffer, offset, len)
                        if (peerBlocked && inbound.size <= PIPE_LOW) {
                            peerBlocked = false
                            engine.drain(id)
                        }
                        return@withContext n
                    }
                    if (writeEnded) return@withContext EOF
                    // Park; deliver()/onPeerCloseWrite complete us with the count, EOF, or a reset.
                    suspendCancellableCoroutine { cont ->
                        reader = ParkedReader(buffer, offset, len, cont)
                        cont.invokeOnCancellation { reader = null }
                    }
                } finally {
                    receiving = false
                }
            }

        // ---- outbound plumbing ----

        override suspend fun send(buffer: ByteArray, offset: Int, len: Int): Unit =
            withContext(engineDispatcher) {
                if (len == 0) return@withContext
                check(!sending) { "concurrent send() on the same pipe" }
                check(!weEnded) { "send() after close()" } // writing past our own half-close is a misuse
                sending = true
                drainCredit = false
                try {
                    var pos = offset
                    val end = offset + len
                    while (pos < end) {
                        if (peerReadClosed) throw BrokenPipeException()
                        val written = engine.write(id, buffer, pos, end - pos)
                        if (written < 0) { peerReadClosed = true; throw BrokenPipeException() }
                        pos += written
                        if (pos < end) {
                            // Partial: the peer's buffer is full. engine.write's trailing microtask
                            // pump may already have drained it and fired DRAIN (credit) — else park.
                            if (drainCredit) { drainCredit = false; continue }
                            suspendCancellableCoroutine { cont ->
                                writer = cont
                                cont.invokeOnCancellation { writer = null }
                            }
                        }
                    }
                } finally {
                    sending = false
                }
            }

        override suspend fun close(reason: String?): Unit = withContext(engineDispatcher) {
            if (!weEnded) {
                weEnded = true
                engine.closeWrite(id, reason)
                maybeReap()
            }
        }

        override suspend fun closeRead(): Unit = withContext(engineDispatcher) {
            if (readClosed) return@withContext
            readClosed = true
            inbound.clear()
            engine.closeRead(id) // tell the peer writer: EPIPE
            val r = reader // an in-flight receive is now a misuse — fail it, don't quietly EOF
            if (r != null) {
                reader = null
                r.cont.resumeWith(Result.failure(IllegalStateException("receive() after closeRead()")))
            }
            maybeReap()
        }

        // No more data either way — drop routing. (The runtime close() reaps everything regardless.)
        private fun maybeReap() {
            if (weEnded && (writeEnded || readClosed)) pipes.remove(id)
        }

        /** Runtime teardown: poison the pipe (later sends get EPIPE, later receives get the reset)
         *  and fail anything parked — a continuation left suspended when the engine thread dies
         *  would never resume (a permanently leaked, hung coroutine). */
        fun failPending(cause: Throwable) {
            writeEnded = true
            peerReadClosed = true
            if (resetReason == null) resetReason = cause.message ?: "runtime closed"
            inbound.clear()
            val r = reader
            if (r != null) { reader = null; r.cont.resumeWith(Result.failure(cause)) }
            val w = writer
            if (w != null) { writer = null; w.resumeWith(Result.failure(cause)) }
        }
    }

    private class ParkedReader(
        val dst: ByteArray,
        val offset: Int,
        val len: Int,
        val cont: CancellableContinuation<Int>,
    )

    /** Cancel handlers, fail anything still parked in [Pipe.send]/[Pipe.receive] (a caller outside
     *  [scope] would otherwise stay suspended forever once the engine thread is gone), free the
     *  engine (on its thread), and release the engine thread. */
    suspend fun close() {
        scope.cancel()
        withContext(engineDispatcher) {
            val cause = QuickJsException("runtime closed")
            val open = pipes.values.toList()
            pipes.clear()
            for (p in open) p.failPending(cause)
            yield() // let the failed continuations unwind while the engine is still alive
            engine.destroy()
        }
        engineDispatcher.close()
    }

    companion object {
        suspend fun create(): QuickJsRuntime {
            val dispatcher = newEngineDispatcher()
            var rtRef: QuickJsRuntime? = null
            val callbacks = object : EngineCallbacks {
                override fun onOpen(id: Int, command: String): Int = rtRef?.onOpen(id, command) ?: -1
                override fun onWrite(id: Int, chunk: NativeChunk): Int = rtRef?.onWrite(id, chunk) ?: -1
                override fun onCloseWrite(id: Int, reason: String?) { rtRef?.onCloseWrite(id, reason) }
                override fun onCloseRead(id: Int) { rtRef?.onCloseRead(id) }
                override fun onDrain(id: Int) { rtRef?.onDrain(id) }
            }
            val engine = withContext(dispatcher) { NativeEngine.create(BOOTSTRAP_JS, callbacks) }
            val rt = QuickJsRuntime(engine, dispatcher)
            rtRef = rt
            rt.installTimers() // host-scheduled setTimeout/setInterval (reserves those commands)
            return rt
        }
    }
}
