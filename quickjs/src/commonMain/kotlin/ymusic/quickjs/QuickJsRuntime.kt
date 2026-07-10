package ymusic.quickjs

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okio.Buffer
import okio.use
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Per-direction pipe buffer bound (bytes) — like a Linux pipe's fixed capacity. A writer whose
 *  write is only partially accepted parks and resumes on the peer's DRAIN notify, which fires once
 *  the buffer drains to [PIPE_LOW]. Must match quickjs_pipe.c (PB_CAP/PB_LOW). */
private const val PIPE_CAP = 64 * 1024
private const val PIPE_LOW = PIPE_CAP / 2

private const val EOF = -1L

/** A live [QuickJsRuntime.onAccept] registration; [remove] unregisters it. */
fun interface AcceptRegistration {
    fun remove()
}

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
@OptIn(ExperimentalAtomicApi::class)
class QuickJsRuntime private constructor(
    private val engine: NativeEngine,
    private val engineDispatcher: CloseableCoroutineDispatcher,
) {

    private val scope = CoroutineScope(engineDispatcher + SupervisorJob())
    private val closed = AtomicBoolean(false)
    private val pipes = HashMap<Int, PipeImpl>()

    // onAccept is called from any thread (the app registers handlers off the engine thread), while
    // onOpen reads on the engine thread — the one piece of state not engine-confined. Guard both
    // ends with a lock.
    private val accepts = HashMap<String, suspend (Pipe) -> Unit>()
    private val acceptsLock = SynchronizedObject()
    private var nextId = 1 // Kotlin opens odd ids; JS opens even.

    /**
     * Evaluate [script] synchronously (to load engine code / register JS `ymix.onAccept` handlers).
     * Returns the completion value's string form, or null; throws [QuickJsException] on a JS error.
     */
    suspend fun load(script: String, filename: String = "<load>"): String? {
        if (closed.load()) throw QuickJsException("runtime closed")
        return withContext(engineDispatcher) {
            // Re-check ON the engine thread: a close() racing the hop CASes `closed` then enqueues
            // its teardown (engine.destroy()) — if that landed first, a stale pre-hop read must not
            // call into the freed engine. The single-thread dispatcher serializes the two blocks.
            if (closed.load()) throw QuickJsException("runtime closed")
            engine.evalSync(script, filename)
        }
    }

    /** Handle pipes JS opens with the exact [command] name, replacing any previous handler. Any
     *  arguments are read inside [handler] from the pipe's bytes — the command is a routing name
     *  only, never carries args. Returns a registration whose [AcceptRegistration.remove]
     *  unregisters it — a no-op once a newer registration has replaced this one. */
    fun onAccept(command: String, handler: suspend (Pipe) -> Unit): AcceptRegistration {
        synchronized(acceptsLock) { accepts[command] = handler }
        return AcceptRegistration {
            synchronized(acceptsLock) {
                if (accepts[command] === handler) accepts.remove(command)
            }
        }
    }

    /** Open a pipe to JS addressed to the [command] name (send any arguments as bytes afterwards).
     *  Fails AT THE OPEN — like ECONNREFUSED — when no JS handler serves [command]. */
    suspend fun openPipe(command: String): Pipe {
        if (closed.load()) throw QuickJsException("runtime closed")
        return withContext(engineDispatcher) {
            // Re-check on the engine thread (see [load]): without this a openPipe racing close()
            // runs engine.open() on the destroyed engine and re-populates the cleared pipes map.
            if (closed.load()) throw QuickJsException("runtime closed")
            val id = nextId
            nextId += 2
            val impl = PipeImpl(id, command)
            // Registered before open() so anything crossing during the call finds the pipe; dropped
            // again on refusal/throw so a failed open never dangles in the registry.
            pipes[id] = impl
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
            impl.pipe
        }
    }

    /** JS opened a pipe to us — route to the registered handler (on the engine thread).
     *  Returns 0 (accepted) or -1 (refused: no handler), which fails JS's open() call itself. */
    internal fun onOpen(id: Int, command: String): Int {
        val handler = synchronized(acceptsLock) { accepts[command] } ?: return -1
        val impl = PipeImpl(id, command)
        pipes[id] = impl
        scope.launch {
            try {
                handler(impl.pipe)
            } catch (e: CancellationException) {
                // Real cancellation propagates; a CancellationException on a still-active job (e.g.
                // withTimeout inside the handler) is a failure and RESETs the pipe like any other.
                if (!isActive) throw e
                impl.closeRead()
                impl.closeWrite(e.message ?: "handler failed")
            } catch (e: Throwable) {
                impl.closeRead()
                impl.closeWrite(e.message ?: "handler failed") // RESET: surface the failure to JS
            }
        }
        return 0
    }

    internal fun onWrite(id: Int, chunk: NativeChunk): Int = pipes[id]?.deliver(chunk) ?: -1

    internal fun onCloseWrite(id: Int, reason: String?) { pipes[id]?.onPeerCloseWrite(reason) }

    internal fun onCloseRead(id: Int) { pipes[id]?.onPeerCloseRead() }

    internal fun onDrain(id: Int) { pipes[id]?.onDrain() }

    private inner class PipeImpl(val id: Int, val command: String) : PipeSource, PipeSink {
        /** The public pair of ends handed to callers — both views over this one state machine. */
        val pipe = Pipe(this, this, command)

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

        /** JS wrote [chunk] (valid only during this call). Rendezvous-first: copy straight into a
         *  parked reader's sink (up to its byteCount — sized at delivery time, so a full chunk can
         *  rendezvous), then the okio buffer up to [PIPE_CAP]. Returns accepted, or -1 (EPIPE). */
        fun deliver(chunk: NativeChunk): Int {
            if (readClosed) return -1
            var pos = 0
            val r = reader
            if (r != null) {
                reader = null
                val n = minOf(chunk.size.toLong(), r.byteCount).toInt()
                copyChunk(r.sink, chunk, 0, n)
                pos = n
                // If cancellation (from any thread) wins against this resume, the bytes are
                // stranded in the abandoned sink while the peer counts them delivered — safe only
                // because the cancelled receive() closes the read side on its way out, so the
                // desynced stream can never be read past.
                r.cont.resume(n.toLong())
            }
            val room = (PIPE_CAP.toLong() - inbound.size).toInt()
            val toBuffer = minOf(chunk.size - pos, room)
            if (toBuffer > 0) {
                copyChunk(inbound, chunk, pos, toBuffer)
                pos += toBuffer
            }
            if (pos < chunk.size) peerBlocked = true
            return pos
        }

        /** Copy `[srcOffset, srcOffset+count)` of [chunk] straight into [dst]'s okio segment arrays. */
        private fun copyChunk(dst: Buffer, chunk: NativeChunk, srcOffset: Int, count: Int) {
            val c = dst.readAndWriteUnsafe(cursor)
            try {
                val oldSize = dst.size
                c.resizeBuffer(oldSize + count)
                var copied = 0
                while (copied < count) {
                    val n = minOf(count - copied, c.end - c.start)
                    chunk.copyInto(c.data!!, c.start, srcOffset + copied, n)
                    copied += n
                    if (copied < count) {
                        check(c.next() != -1)
                    }
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
                if (r != null) { reader = null; r.cont.resumeWithException(PipeResetException(reason)) }
            } else if (r != null) { // a parked reader implies an empty buffer -> EOF now
                reader = null
                r.cont.resume(EOF)
            }
            maybeReap()
        }

        fun onPeerCloseRead() {
            peerReadClosed = true
            val w = writer
            if (w != null) { writer = null; w.resumeWithException(BrokenPipeException()) }
        }

        fun onDrain() {
            val w = writer
            if (w != null) { writer = null; w.resume(Unit) } else drainCredit = true
        }

        override suspend fun receive(sink: Buffer, byteCount: Long): Long {
            // Checked before the dispatcher hop: after runtime close() the engine thread is gone
            // (dispatching there is at best a fallback thread, at worst an error).
            if (closed.load()) throw QuickJsException("runtime closed")
            return withContext(engineDispatcher) {
                // A REAPED pipe (left the pipes map on maybeReap) is not poisoned by failPending, so
                // its own-side flags can't guard a race with teardown — the engine-thread `closed`
                // recheck does (a receive draining buffered data still calls engine.drain).
                if (closed.load()) throw QuickJsException("runtime closed")
                require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
                if (byteCount == 0L) return@withContext 0L
                check(!readClosed) { "receive() after the read side was closed (closeRead() or an aborted receive)" }
                check(!receiving) { "concurrent receive() on the same pipe" }
                receiving = true
                try {
                    resetReason?.let { throw PipeResetException(it) }
                    if (inbound.size > 0L) {
                        val n = inbound.read(sink, byteCount) // whole-segment moves, no copy
                        if (peerBlocked && inbound.size <= PIPE_LOW) {
                            peerBlocked = false
                            engine.drain(id)
                        }
                        return@withContext n
                    }
                    if (writeEnded) return@withContext -1L
                    // Park with the sink itself; deliver() copies straight into it (sized at
                    // delivery time) and resumes us with the count — or EOF / a reset / a failure.
                    // No invokeOnCancellation: [reader] is engine-confined, and the catch below
                    // clears it on the engine thread; a cancellation cleanup would run on the
                    // canceller's thread and race deliver().
                    suspendCancellableCoroutine { cont ->
                        reader = ParkedReader(sink, byteCount, cont)
                    }
                } catch (t: Throwable) {
                    // A PipeResetException is the peer's terminal signal, not a stranded read: leave
                    // the read side open so every later receive() re-throws it (the KDoc contract),
                    // rather than a read-closed IllegalStateException. Any other abort (cancellation
                    // included) may have stranded bytes in a sink nobody will read — close the read
                    // side so the pipe lands in ONE predictable state: done reading, peer gets EPIPE.
                    // Deliberate fire-and-forget: cleanup must not mask the abort cause.
                    if (t !is PipeResetException) runCatching { closeReadNow(t) }
                    throw t
                } finally {
                    receiving = false
                }
            }
        }

        // ---- outbound plumbing ----

        override suspend fun send(source: Buffer, byteCount: Long) {
            if (closed.load()) throw QuickJsException("runtime closed")
            withContext(engineDispatcher) {
                if (closed.load()) throw QuickJsException("runtime closed") // reaped-pipe race, see receive()
                require(byteCount in 0..source.size) {
                    "byteCount $byteCount out of range (buffer size ${source.size})"
                }
                if (byteCount == 0L) return@withContext
                check(!sending) { "concurrent send() on the same pipe" }
                if (weEnded) throw SendClosedException("send() after close()") // writing past our own half-close
                sending = true
                drainCredit = false
                try {
                    var remaining = byteCount
                    while (remaining > 0L) {
                        // Snapshot the head segment's range (cursor detached again before any suspend).
                        var data: ByteArray? = null
                        var start = 0
                        var n = 0
                        source.readUnsafe(cursor).use { c ->
                            c.seek(0L)
                            data = c.data
                            start = c.start
                            n = minOf(remaining, (c.end - c.start).toLong()).toInt()
                        }
                        sendRange(data!!, start, n) // the segment array crosses the FFI pinned, zero-copy
                        source.skip(n.toLong()) // consume only once the peer fully accepted it
                        remaining -= n
                    }
                } catch (t: Throwable) {
                    // A send that aborts (cancellation included) strands the stream at an unknowable
                    // position: the peer would wait forever for (or misparse) the rest, and a retry
                    // would resend the unskipped tail it already consumed. RESET so the pipe lands
                    // in ONE predictable state: done writing, the peer's read fails fast.
                    // Deliberate fire-and-forget: cleanup must not mask the abort cause.
                    if (!weEnded && !peerReadClosed) {
                        weEnded = true
                        runCatching {
                            engine.closeWrite(id, "send aborted mid-stream: ${t.message ?: "cancelled"}")
                        }
                        maybeReap()
                    }
                    throw t
                } finally {
                    sending = false
                }
            }
        }

        private suspend fun sendRange(buffer: ByteArray, offset: Int, len: Int) {
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
                    // Same lost-wakeup window for closeRead: the pump can run the peer's
                    // closeRead() during THIS engine.write call (onPeerCloseRead finds no parked
                    // writer, only sets the flag). Parking after that would hang forever — no
                    // DRAIN or close event is ever coming from a peer that stopped reading.
                    if (peerReadClosed) throw BrokenPipeException()
                    // [writer] is engine-confined and set/cleared in this one spot: onDrain /
                    // onPeerCloseRead null it on a normal wake, this finally covers an abort. No
                    // invokeOnCancellation — it would run on the canceller's thread and race onDrain.
                    try {
                        suspendCancellableCoroutine { cont -> writer = cont }
                    } finally {
                        writer = null
                    }
                }
            }
        }

        // NonCancellable: these are cleanup calls invoked from `finally`, so they must run even
        // when the caller's job is already cancelled. The closed latch skips them after teardown.
        override suspend fun close(reason: String?) = closeWrite(reason) // PipeSink

        override suspend fun close() = closeRead() // PipeSource

        suspend fun closeWrite(reason: String?) {
            // weEnded/readClosed are engine-confined, but as monotonic flags an off-thread read
            // can only be stale-false (take the hop and recheck) — never wrongly true. Skips the
            // dispatcher hop on repeated closes (cleanup paths close idempotently by design).
            if (closed.load() || weEnded) return
            withContext(engineDispatcher + NonCancellable) {
                // A reaped pipe's weEnded may already be true (reaped via our END); but if reaped via
                // peer-END + our-still-open-write, the `closed` recheck is what stops closeWrite from
                // reaching a destroyed engine when it lands behind teardown.
                if (closed.load()) return@withContext
                if (!weEnded) {
                    weEnded = true
                    val w = writer
                    // A send parked at the cap has undelivered bytes: an END-close that must abort
                    // it delivers a RESET instead — a clean END would truncate the peer's stream. A
                    // RESET-close passes its own reason through. Either way the parked send is
                    // force-failed (never left hung) with SendClosedException — self-caused (our
                    // close), NOT the peer-caused BrokenPipeException; weEnded is set so the send's
                    // catch won't re-RESET.
                    val effectiveReason = reason ?: if (w != null) "closed while a send was in progress" else null
                    engine.closeWrite(id, effectiveReason)
                    if (w != null) { writer = null; w.resumeWithException(SendClosedException("send aborted by close()")) }
                    maybeReap()
                }
            }
        }

        suspend fun closeRead() {
            if (closed.load() || readClosed) return
            withContext(engineDispatcher + NonCancellable) {
                // The reaped-pipe UAF: a reaped pipe (readClosed still false) is not poisoned by
                // failPending, so only this engine-thread recheck stops closeReadNow → engine.closeRead
                // on the destroyed engine when the everyday `finally { source.close() }` races close().
                if (closed.load()) return@withContext
                closeReadNow(null)
            }
        }

        /** [cause] is the aborted receive's failure when the abort path (not an explicit
         *  [closeRead]) closes the read side. */
        private fun closeReadNow(cause: Throwable?) {
            if (readClosed) return
            readClosed = true
            inbound.clear()
            engine.closeRead(id) // tell the peer writer: EPIPE
            val r = reader // an in-flight receive is now a misuse — fail it, don't quietly EOF
            if (r != null) {
                reader = null
                r.cont.resumeWithException(cause ?: IllegalStateException("receive() after closeRead()"))
            }
            maybeReap()
        }

        // No more data either way — drop routing. (The runtime close() reaps everything regardless.)
        private fun maybeReap() {
            if (weEnded && (writeEnded || readClosed)) pipes.remove(id)
        }

        /** Runtime teardown: poison the pipe on BOTH sides and fail anything parked — a
         *  continuation left suspended when the engine thread dies would never resume (a
         *  permanently leaked, hung coroutine). The own-side flags ([weEnded], [readClosed])
         *  matter as much as the peer-side ones: an op that still lands on the engine queue
         *  behind [close]'s `engine.destroy()` (e.g. `finally { pipe.close() }` racing runtime
         *  close) must bail on the flags instead of calling into the freed engine. */
        fun failPending(cause: Throwable) {
            writeEnded = true
            peerReadClosed = true
            weEnded = true
            readClosed = true
            if (resetReason == null) resetReason = cause.message ?: "runtime closed"
            inbound.clear()
            val r = reader
            if (r != null) { reader = null; r.cont.resumeWithException(cause) }
            val w = writer
            if (w != null) { writer = null; w.resumeWithException(cause) }
        }
    }

    /** A parked receive: deliver() copies straight into [sink] (up to [byteCount]) and resumes. */
    private class ParkedReader(
        val sink: Buffer,
        val byteCount: Long,
        val cont: CancellableContinuation<Long>,
    )

    /** Cancel handlers, fail anything still parked in [Pipe.send]/[Pipe.receive] (a caller outside
     *  [scope] would otherwise stay suspended forever once the engine thread is gone), free the
     *  engine (on its thread), and release the engine thread. Idempotent: only the first call
     *  tears down; later calls return immediately (the engine must never be destroyed twice, and
     *  the closed dispatcher must never be dispatched to again). */
    suspend fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        scope.cancel()
        // Cleanup must complete even when the caller is already cancelled — the standard
        // `finally { rt.close() }` on a cancelled job — so the teardown hop is NonCancellable
        // (a plain withContext would skip the whole block, silently leaking the engine).
        withContext(engineDispatcher + NonCancellable) {
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
        /** [script] is the engine JS (the :engine-js bundle) evaluated once at creation. It must
         *  define the transport globals (`__jsOpen`/`__jsClose`/`__jsCloseRead`/`__jsDrain`,
         *  `ymix`) — the native engine captures them right after this one eval, so the script is
         *  only accepted HERE, never loaded onto a live runtime. This module ships no JS. */
        suspend fun create(script: String): QuickJsRuntime {
            val dispatcher = newEngineDispatcher()
            var engine: NativeEngine? = null
            try {
                var rtRef: QuickJsRuntime? = null
                val callbacks = object : EngineCallbacks {
                    override fun onOpen(id: Int, command: String): Int = rtRef?.onOpen(id, command) ?: -1
                    override fun onWrite(id: Int, chunk: NativeChunk): Int = rtRef?.onWrite(id, chunk) ?: -1
                    override fun onCloseWrite(id: Int, reason: String?) { rtRef?.onCloseWrite(id, reason) }
                    override fun onCloseRead(id: Int) { rtRef?.onCloseRead(id) }
                    override fun onDrain(id: Int) { rtRef?.onDrain(id) }
                }
                // Assign INSIDE the block: prompt-cancellation throws a CancellationException on the
                // way back out of a withContext whose body already completed, so `engine = <result>`
                // would never run and the catch would see null and leak the freshly-created engine.
                // Assigned in-block, the engine is visible to the catch on that path.
                withContext(dispatcher) { engine = NativeEngine.create(script, callbacks) }
                val rt = QuickJsRuntime(engine!!, dispatcher)
                rtRef = rt
                rt.installTimers() // host-scheduled setTimeout/setInterval (reserves those commands)
                return rt
            } catch (t: Throwable) {
                // A partial create must free the engine thread (and the native engine, if it got
                // that far) — otherwise each failed/retried create() leaks a dispatcher thread.
                engine?.let { e -> runCatching { withContext(dispatcher + NonCancellable) { e.destroy() } } }
                dispatcher.close()
                throw t
            }
        }
    }
}
