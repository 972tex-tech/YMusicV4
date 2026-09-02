package io.ymusic.mse

import io.ymusic.mse.cinterop.mse_host_copy_frame
import io.ymusic.mse.cinterop.mse_host_create
import io.ymusic.mse.cinterop.mse_host_destroy
import io.ymusic.mse.cinterop.mse_host_frames
import io.ymusic.mse.cinterop.mse_host_vtable
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
actual object MseHostNative {
    private var frameScratch = ByteArray(0)

    actual fun vtable(): Long = mse_host_vtable()?.rawValue?.toLong() ?: 0L

    actual fun create(): Long = mse_host_create()?.rawValue?.toLong() ?: 0L

    actual fun destroy(handle: Long) {
        if (handle == 0L) return
        mse_host_destroy(handle.toCPointer<COpaque>())
    }

    actual fun frames(handle: Long): Int {
        if (handle == 0L) return 0
        return mse_host_frames(handle.toCPointer<COpaque>())
    }

    actual fun copyFrame(handle: Long, afterGen: Int): NativeMseFrame? {
        if (handle == 0L) return null
        val host = handle.toCPointer<COpaque>() ?: return null
        return memScoped {
            val w = alloc<IntVar>()
            val ht = alloc<IntVar>()
            val gen = alloc<IntVar>()
            val need = mse_host_copy_frame(host, null, 0, w.ptr, ht.ptr, gen.ptr)
            if (need <= 0 || gen.value == afterGen) return@memScoped null
            if (frameScratch.size < need) frameScratch = ByteArray(need)
            val n = frameScratch.usePinned { pinned ->
                mse_host_copy_frame(
                    host,
                    pinned.addressOf(0).reinterpret<UByteVar>(),
                    need,
                    w.ptr,
                    ht.ptr,
                    gen.ptr,
                )
            }
            if (n <= 0 || n > frameScratch.size) return@memScoped null
            NativeMseFrame(w.value, ht.value, gen.value, frameScratch)
        }
    }
}
