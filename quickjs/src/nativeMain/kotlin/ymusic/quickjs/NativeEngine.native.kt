package ymusic.quickjs

import cnames.structs.QjsEngine
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.memcpy
import platform.posix.size_t
import ymusic.quickjs.cinterop.QJS_OP_CLOSE_READ
import ymusic.quickjs.cinterop.QJS_OP_CLOSE_WRITE
import ymusic.quickjs.cinterop.QJS_OP_DRAIN
import ymusic.quickjs.cinterop.QJS_OP_OPEN
import ymusic.quickjs.cinterop.QJS_OP_WRITE
import ymusic.quickjs.cinterop.qjs_close
import ymusic.quickjs.cinterop.qjs_close_read
import ymusic.quickjs.cinterop.qjs_close_write
import ymusic.quickjs.cinterop.qjs_create
import ymusic.quickjs.cinterop.qjs_drain
import ymusic.quickjs.cinterop.qjs_eval_sync
import ymusic.quickjs.cinterop.qjs_free
import ymusic.quickjs.cinterop.qjs_open
import ymusic.quickjs.cinterop.qjs_write

/** JS bytes as a raw pointer (valid only during onWrite; the mux consumes it synchronously). */
@OptIn(ExperimentalForeignApi::class)
internal actual class NativeChunk(
    private val ptr: CPointer<UByteVar>,
    actual val size: Int,
) {
    actual fun copyInto(dst: ByteArray, dstOffset: Int, srcOffset: Int, count: Int) {
        if (count == 0) return
        dst.usePinned { pinned ->
            memcpy(pinned.addressOf(dstOffset), ptr + srcOffset, count.convert())
        }
    }
}

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

    actual fun open(id: Int, command: String): Int = qjs_open(engine, id.toUInt(), command)

    actual fun write(id: Int, buffer: ByteArray, offset: Int, len: Int): Int {
        if (len <= 0) return 0
        return buffer.usePinned { pinned ->
            qjs_write(engine, id.toUInt(), pinned.addressOf(offset).reinterpret(), len.convert())
        }
    }

    actual fun closeWrite(id: Int, reason: String?) = qjs_close_write(engine, id.toUInt(), reason)

    actual fun closeRead(id: Int) = qjs_close_read(engine, id.toUInt())

    actual fun drain(id: Int) = qjs_drain(engine, id.toUInt())

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
 *  Ops per quickjs_native.h: OPEN (data = command; return 0 accepted / -1 refused), WRITE
 *  (data/len = payload, valid only during the call; return = accepted / -1 EPIPE), CLOSE_WRITE
 *  (data = RESET reason or NULL for END), CLOSE_READ, DRAIN.
 *
 *  A Kotlin exception must NEVER unwind through this C boundary (that aborts the process on
 *  Kotlin/Native) — catch, report, and fail the op with -1 (refused/EPIPE at the protocol level). */
@OptIn(ExperimentalForeignApi::class)
private fun inboundTrampoline(user: COpaquePointer?, op: Int, id: UInt, data: CPointer<UByteVar>?, len: size_t): Int {
    if (user == null) return -1
    val cb = user.asStableRef<EngineCallbacks>().get()
    val idInt = id.toInt()
    return try {
        when (op) {
            QJS_OP_OPEN -> cb.onOpen(idInt, data?.reinterpret<ByteVar>()?.toKString() ?: "")
            QJS_OP_WRITE -> {
                val n = len.toInt()
                if (data == null || n <= 0) 0 else cb.onWrite(idInt, NativeChunk(data, n))
            }
            QJS_OP_CLOSE_WRITE -> { cb.onCloseWrite(idInt, data?.reinterpret<ByteVar>()?.toKString()); 0 }
            QJS_OP_CLOSE_READ -> { cb.onCloseRead(idInt); 0 }
            QJS_OP_DRAIN -> { cb.onDrain(idInt); 0 }
            else -> 0
        }
    } catch (e: Throwable) {
        println("quickjs: host callback failed (op=$op id=$idInt): $e")
        -1
    }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
internal actual fun newEngineDispatcher(): CloseableCoroutineDispatcher =
    newSingleThreadContext("quickjs-engine")
