package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A QuickJS engine reached over an in-process pipe protocol, on every KMP target. JS execution is
 * confined to a single thread; the FFI boundary ([NativeEngine]) shuttles three operations per
 * direction (open/data/close). On top of it this multiplexes logical [Pipe]s: either side can
 * [openPipe] a command to the other, or [onAccept] commands the other opens. Payloads are raw
 * bytes, so binary and streaming work; the command/reason cross as native strings.
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
     *  from the pipe's frames — the command is a routing name only, never carries args. */
    fun onAccept(command: String, handler: suspend (Pipe) -> Unit) {
        accepts[command] = handler
    }

    /** Open a pipe to JS addressed to the [command] name (send any arguments as frames afterwards). */
    suspend fun openPipe(command: String): Pipe = withContext(engineDispatcher) {
        val id = nextId
        nextId += 2
        val pipe = PipeImpl(id, command)
        pipes[id] = pipe
        engine.open(id, command)
        pipe
    }

    /** JS opened a pipe to us — route to the registered handler (on the engine thread). */
    internal fun onOpen(id: Int, command: String) {
        val pipe = PipeImpl(id, command)
        pipes[id] = pipe
        val handler = accepts[command]
        if (handler != null) {
            scope.launch {
                try {
                    handler(pipe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    engine.closePipe(id, e.message ?: "handler error")
                    pipes.remove(id)
                }
            }
        } else {
            engine.closePipe(id, "no handler: $command")
            pipes.remove(id)
        }
    }

    internal fun onData(id: Int, bytes: ByteArray) {
        pipes[id]?.deliver(bytes)
    }

    // END (reason null) or RESET (reason non-null): either way the peer sends no more.
    internal fun onClose(id: Int, reason: String?) {
        pipes[id]?.endInbound()
    }

    private inner class PipeImpl(val id: Int, override val command: String) : Pipe {
        // Arrived payloads; drained through (head, headPos) as a byte stream.
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private var head: ByteArray? = null
        private var headPos = 0
        private var weClosed = false
        private var receiving = false

        override suspend fun send(buffer: ByteArray, offset: Int, len: Int) {
            if (len == 0) return
            withContext(engineDispatcher) { engine.data(id, buffer, offset, len) }
        }

        // One outstanding receive at a time: overlapping calls would race head/headPos. A second
        // concurrent receive is a bug, so fail fast instead of queueing waiters.
        override suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int {
            if (len == 0) return 0
            check(!receiving) { "concurrent receive() on the same pipe" }
            receiving = true
            try {
                var h = head
                if (h == null || headPos >= h.size) {
                    h = inbox.receiveCatching().getOrNull() ?: return -1 // closed + drained
                    head = h
                    headPos = 0
                }
                val n = minOf(len, h.size - headPos)
                h.copyInto(buffer, offset, headPos, headPos + n)
                headPos += n
                return n
            } finally {
                receiving = false
            }
        }

        // Half-close: signal "done sending" (END). We can still receive until the peer ends, so we
        // don't drop routing here — only when the peer's close arrives (endInbound).
        override suspend fun close() {
            withContext(engineDispatcher) {
                if (!weClosed) {
                    weClosed = true
                    engine.closePipe(id, null)
                }
            }
        }

        fun deliver(bytes: ByteArray) {
            inbox.trySend(bytes)
        }

        fun endInbound() {
            inbox.close()
            pipes.remove(id) // peer won't send more — drop inbound routing
        }
    }

    /** Cancel handlers, free the engine (on its thread), and release the engine thread. */
    suspend fun close() {
        scope.cancel()
        withContext(engineDispatcher) { engine.destroy() }
        engineDispatcher.close()
    }

    companion object {
        suspend fun create(): QuickJsRuntime {
            val dispatcher = newEngineDispatcher()
            var rtRef: QuickJsRuntime? = null
            val callbacks = object : EngineCallbacks {
                override fun onOpen(id: Int, command: String) { rtRef?.onOpen(id, command) }
                override fun onData(id: Int, bytes: ByteArray) { rtRef?.onData(id, bytes) }
                override fun onClose(id: Int, reason: String?) { rtRef?.onClose(id, reason) }
            }
            val engine = withContext(dispatcher) { NativeEngine.create(BOOTSTRAP_JS, callbacks) }
            val rt = QuickJsRuntime(engine, dispatcher)
            rtRef = rt
            return rt
        }
    }
}
