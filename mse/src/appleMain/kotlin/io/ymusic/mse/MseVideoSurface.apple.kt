package io.ymusic.mse

actual class MseVideoSurface {
    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    var host: Long = 0L
}
