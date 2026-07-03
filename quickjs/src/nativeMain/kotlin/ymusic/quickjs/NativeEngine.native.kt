package ymusic.quickjs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.StableRef
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.size_t
import cnames.structs.QjsEngine
import ymusic.quickjs.cinterop.qjs_close
import ymusic.quickjs.cinterop.qjs_close_pipe
import ymusic.quickjs.cinterop.qjs_create
import ymusic.quickjs.cinterop.qjs_data
import ymusic.quickjs.cinterop.qjs_eval_sync
import ymusic.quickjs.cinterop.qjs_free
import ymusic.quickjs.cinterop.qjs_open

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeEngine private constructor(
    private val engine: CPointer<QjsEngine>,
    private val sinkRef: StableRef<EngineCallbacks>,
) {
    actual fun evalSync(script: String, filename: String): String? = memScoped {
        val isError = alloc<IntVar>()
        val res: CPointer<ByteVar>? = qjs_eval_sync(engine, script, filename, isError.ptr)
        val out = res?.toKString()
        if (res != null) qjs_free(res)
        if (isError.value != 0) throw QuickJsException(out ?: "error")
        out
    }

    actual fun open(id: Int, command: String) = qjs_open(engine, id.toUInt(), command)

    actual fun data(id: Int, buffer: ByteArray, offset: Int, len: Int) {
        if (len <= 0) return
        buffer.usePinned { pinned ->
            qjs_data(engine, id.toUInt(), pinned.addressOf(offset).reinterpret(), len.convert())
        }
    }

    actual fun closePipe(id: Int, reason: String?) = qjs_close_pipe(engine, id.toUInt(), reason)

    actual fun destroy() {
        qjs_close(engine)
        sinkRef.dispose()
    }

    actual companion object {
        actual fun create(bootstrap: String, callbacks: EngineCallbacks): NativeEngine = memScoped {
            val ref = StableRef.create(callbacks)
            val errVar = alloc<CPointerVar<ByteVar>>()
            val eng = qjs_create(bootstrap, staticCFunction(::inboundTrampoline), ref.asCPointer(), errVar.ptr)
            if (eng == null) {
                val msg = errVar.value?.toKString()
                errVar.value?.let { qjs_free(it) }
                ref.dispose()
                throw QuickJsException(msg ?: "quickjs create failed")
            }
            NativeEngine(eng, ref)
        }
    }
}

/** C -> Kotlin inbound callback. [user] is the StableRef to the engine's [EngineCallbacks].
 *  op 0=open, 1=data, 2=close. For open/close, [data] is a NUL-terminated UTF-8 cstring
 *  (NULL reason on close = END); for data it is [len] raw bytes. */
@OptIn(ExperimentalForeignApi::class)
private fun inboundTrampoline(user: COpaquePointer?, op: Int, id: UInt, data: CPointer<UByteVar>?, len: size_t) {
    if (user == null) return
    val cb = user.asStableRef<EngineCallbacks>().get()
    val idInt = id.toInt()
    when (op) {
        0 -> cb.onOpen(idInt, data?.reinterpret<ByteVar>()?.toKString() ?: "")
        1 -> {
            val n = len.toInt()
            cb.onData(idInt, if (data != null && n > 0) data.readBytes(n) else ByteArray(0))
        }
        else -> cb.onClose(idInt, data?.reinterpret<ByteVar>()?.toKString())
    }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun newEngineDispatcher(): CloseableCoroutineDispatcher =
    newSingleThreadContext("quickjs-engine")
