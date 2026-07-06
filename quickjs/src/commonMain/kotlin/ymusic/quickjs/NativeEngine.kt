package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher

/** Flow-control notify code (both directions): the reader drained its buffer to the low-water
 *  mark — wake the parked writer. Must match `QJS_NOTIFY_DRAIN` / bootstrap.js. */
internal const val NOTIFY_DRAIN = 1

/**
 * A chunk of JS-owned bytes crossing JS -> host, valid ONLY during the [EngineCallbacks.onWrite]
 * call that delivers it (jvm: a direct ByteBuffer over the typed array's memory; native: a raw
 * pointer). The mux must consume it synchronously — copy into the parked reader's buffer and/or
 * its inbound okio buffer — never retain it.
 */
internal expect class NativeChunk {
    val size: Int

    /** Copy `[srcOffset, srcOffset+count)` of the chunk into [dst] at [dstOffset]. */
    fun copyInto(dst: ByteArray, dstOffset: Int, srcOffset: Int, count: Int)
}

/**
 * Inbound callbacks: invoked (on the engine thread) when JS reaches the host. The multiplexer
 * ([QuickJsRuntime]) implements these; the transport never routes, it only shuttles operations.
 */
internal interface EngineCallbacks {
    fun onOpen(id: Int, command: String)

    /** JS wrote bytes to us. Consume [chunk] synchronously (parked reader first, then the inbound
     *  buffer up to its cap) and return the accepted byte count — less than `chunk.size` means the
     *  JS writer parks until we [NativeEngine.notify] a DRAIN — or -1 if our read end is closed. */
    fun onWrite(id: Int, chunk: NativeChunk): Int

    /** JS closed its write end: [reason] `null` = END (receive returns -1 once drained); non-null =
     *  RESET (pending data is discarded and receive throws [PipeResetException]). */
    fun onCloseWrite(id: Int, reason: String?)

    /** JS abandoned its read end: our next send throws [BrokenPipeException]. */
    fun onCloseRead(id: Int)

    /** Flow control: [NOTIFY_DRAIN] — JS drained its buffer, wake our parked writer. */
    fun onNotify(id: Int, code: Int)
}

/**
 * The low-level engine boundary over QuickJS. Backed by JNI on jvm/android and by cinterop on
 * Kotlin/Native; both speak the same op-per-direction contract, so [QuickJsRuntime]'s mux is fully
 * shared. Commands and RESET reasons cross as native strings; payloads cross zero-copy (a one-shot
 * view of the caller's bytes, valid only during the call).
 */
internal expect class NativeEngine {
    /** Evaluate [script]; returns the completion value's string form (or null). Throws on JS error. */
    fun evalSync(script: String, filename: String): String?

    /** Host -> JS. */
    fun open(id: Int, command: String)

    /** Deliver `buffer[offset, offset+len)` to the JS reader; returns the accepted byte count
     *  (< len = backpressure, wait for a DRAIN notify), or -1 if it closed its read end. */
    fun write(id: Int, buffer: ByteArray, offset: Int, len: Int): Int

    /** Write-side half-close: [reason] `null` = END, non-null = RESET. */
    fun closeWrite(id: Int, reason: String?)

    /** Abandon our read end: the JS writer's next send gets EPIPE. */
    fun closeRead(id: Int)

    /** Send a flow-control signal ([NOTIFY_DRAIN]) to JS. */
    fun notify(id: Int, code: Int)

    /** Tear down the engine and release native resources. */
    fun destroy()

    companion object {
        fun create(bootstrap: String, callbacks: EngineCallbacks): NativeEngine
    }
}

/** A single-thread dispatcher confining all engine calls (QuickJS is not thread-safe). */
internal expect fun newEngineDispatcher(): CloseableCoroutineDispatcher
