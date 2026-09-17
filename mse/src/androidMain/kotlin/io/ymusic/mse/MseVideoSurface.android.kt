package io.ymusic.mse

import android.view.Surface

actual class MseVideoSurface(val surface: Surface? = null) {
    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    var host: Long = 0L

    actual fun setBlitSize(width: Int, height: Int) = Unit

    actual fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)
}
