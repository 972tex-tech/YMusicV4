package io.ymusic.mse

import android.view.Surface

actual class MseVideoSurface(val surface: Surface? = null) {
    /** C `MseHost*` as a Long, set by bindNativeMse. 0 = none. */
    var host: Long = 0L

    actual fun setBlitSize(width: Int, height: Int) = Unit

    actual fun presentedFrames(): Int = if (host == 0L) 0 else MseHostNative.frames(host)

    actual fun setVolume(volume: Float) {
        if (host == 0L) return
        MseHostNative.setVolume(host, volume)
    }

    actual fun setAudioEffect(
        bands: FloatArray,
        bass: Float,
        virtualizer: Float,
    ) {
        if (host == 0L) return
        MseHostNative.setEq(host, bands)
        MseHostNative.setBass(host, bass)
        MseHostNative.setVirtualizer(host, virtualizer)
    }

    actual fun pcmLevels(out: FloatArray): Int =
        if (host == 0L || out.isEmpty()) 0 else MseHostNative.pcmLevels(host, out)
}
