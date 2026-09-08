package io.ymusic.dynamicengine.player

import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import kmpcommon.error.TechnicalException
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun bindNativeMse(runtime: QuickJsRuntime, surface: MseVideoSurface): NativeMseHost {
    val host = MseHostNative.create()
    if (host == 0L) throw TechnicalException("E80070", "native MSE is not wired (no iOS libav)")
    surface.host = host
    runtime.attachMseHost(host, MseHostNative.vtable())
    return object : NativeMseHost {
        override fun setVideoSurface(surface: MseVideoSurface?) {
            MseHostNative.setVideoLayer(host, surface?.layer)
        }

        override fun close() {
            MseHostNative.setVideoLayer(host, null)
            runtime.attachMseHost(0, 0)
            MseHostNative.destroy(host)
        }
    }
}
