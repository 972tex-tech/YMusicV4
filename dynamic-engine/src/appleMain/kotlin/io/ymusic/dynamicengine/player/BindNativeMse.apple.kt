package io.ymusic.dynamicengine.player

import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.quickjs.QuickJsRuntime
import kotlinx.cinterop.ExperimentalForeignApi
import projectcommon.error.TechnicalException

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

        override fun setSpeed(speed: Float, tempo: Float) {
            MseHostNative.setSpeed(host, speed, tempo)
        }

        override fun close() {
            // A successor session may already own the shared surface.
            if (surface.host == host) surface.host = 0L
            MseHostNative.setVideoLayer(host, null)
            runtime.attachMseHost(0, 0)
            MseHostNative.destroy(host)
        }
    }
}
