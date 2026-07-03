package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher

/**
 * Inbound callbacks: invoked (on the engine thread) when JS reaches the host. The multiplexer
 * ([QuickJsRuntime]) implements these; the transport never routes, it only shuttles operations.
 */
internal interface EngineCallbacks {
    fun onOpen(id: Int, command: String)
    fun onData(id: Int, bytes: ByteArray)
    /** [reason] is null for a normal half-close (END), non-null for an error/RESET. */
    fun onClose(id: Int, reason: String?)
}

/**
 * The low-level engine boundary over QuickJS. Backed by JNI on jvm/android and by cinterop on
 * Kotlin/Native; both speak the same op-per-direction contract (open/data/close each way), so
 * [QuickJsRuntime]'s mux is fully shared. Command/reason cross as native strings; payloads as bytes.
 */
internal expect class NativeEngine {
    /** Evaluate [script]; returns the completion value's string form (or null). Throws on JS error. */
    fun evalSync(script: String, filename: String): String?

    /** Host -> JS. */
    fun open(id: Int, command: String)
    fun data(id: Int, buffer: ByteArray, offset: Int, len: Int)
    /** [reason] null = normal half-close (END), non-null = error/RESET. */
    fun closePipe(id: Int, reason: String?)

    /** Tear down the engine and release native resources. */
    fun destroy()

    companion object {
        fun create(bootstrap: String, callbacks: EngineCallbacks): NativeEngine
    }
}

/** A single-thread dispatcher confining all engine calls (QuickJS is not thread-safe). */
internal expect fun newEngineDispatcher(): CloseableCoroutineDispatcher
