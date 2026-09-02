package io.ymusic.dynamicengine.player

import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseSink
import io.ymusic.quickjs.QuickJsRuntime

internal actual fun bindNativeMse(runtime: QuickJsRuntime, surface: MseVideoSurface): NativeMseHost {
    val host = MseHostNative.create()
    if (host == 0L) error("mse_host_create failed")
    val sink = NativeMseSink(host, surface)
    runtime.attachMseHost(host, MseHostNative.vtable())
    return object : NativeMseHost {
        override fun setVideoSurface(surface: MseVideoSurface?) = Unit
        override fun close() = sink.destroy()
    }
}
