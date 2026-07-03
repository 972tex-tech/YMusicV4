package ymusic.quickjs

import java.util.concurrent.ConcurrentHashMap

/** Load the `quickjsjni` shared library (from a dir on the JVM, from the APK on Android). */
internal expect fun loadQuickJsLibrary()

/**
 * The JNI boundary (jvm + android) — maps to `quickjs_jni.c`. C invokes the `on*` statics when JS
 * reaches the host; they route to the per-engine [EngineCallbacks] registered in [register].
 */
internal object QuickJsNative {
    private val sinks = ConcurrentHashMap<Long, EngineCallbacks>()

    init {
        loadQuickJsLibrary()
    }

    fun register(handle: Long, sink: EngineCallbacks) {
        sinks[handle] = sink
    }

    fun unregister(handle: Long) {
        sinks.remove(handle)
    }

    @JvmStatic
    fun onOpen(handle: Long, id: Int, command: String) {
        sinks[handle]?.onOpen(id, command)
    }

    @JvmStatic
    fun onData(handle: Long, id: Int, bytes: ByteArray) {
        sinks[handle]?.onData(id, bytes)
    }

    @JvmStatic
    fun onClose(handle: Long, id: Int, reason: String?) {
        sinks[handle]?.onClose(id, reason)
    }

    external fun create(bootstrap: String): Long
    external fun evalSync(handle: Long, script: String, filename: String): String?
    external fun open(handle: Long, id: Int, command: String)
    external fun data(handle: Long, id: Int, bytes: ByteArray, off: Int, len: Int)
    external fun closePipe(handle: Long, id: Int, reason: String?)
    external fun close(handle: Long)
}
