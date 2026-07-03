package ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal actual class NativeEngine private constructor(private val handle: Long) {
    actual fun evalSync(script: String, filename: String): String? =
        QuickJsNative.evalSync(handle, script, filename)

    actual fun open(id: Int, command: String) = QuickJsNative.open(handle, id, command)

    actual fun data(id: Int, buffer: ByteArray, offset: Int, len: Int) =
        QuickJsNative.data(handle, id, buffer, offset, len)

    actual fun closePipe(id: Int, reason: String?) = QuickJsNative.closePipe(handle, id, reason)

    actual fun destroy() {
        QuickJsNative.unregister(handle)
        QuickJsNative.close(handle)
    }

    actual companion object {
        actual fun create(bootstrap: String, callbacks: EngineCallbacks): NativeEngine {
            val handle = QuickJsNative.create(bootstrap)
            QuickJsNative.register(handle, callbacks)
            return NativeEngine(handle)
        }
    }
}

internal actual fun newEngineDispatcher(): CloseableCoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "quickjs-engine").apply { isDaemon = true } }
        .asCoroutineDispatcher()
