package io.ymusic.mse

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

actual class MseVideoSurface {
    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    var host: Long = 0L

    /** Borrowed `AVSampleBufferDisplayLayer*`. Kotlin owns the layer. */
    @OptIn(ExperimentalForeignApi::class)
    var layer: COpaquePointer? = null

    actual fun setBlitSize(width: Int, height: Int) = Unit

    actual fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)
}
