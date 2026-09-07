package io.ymusic.mse

expect object MseHostNative {
    fun vtable(): Long

    fun create(): Long

    fun destroy(handle: Long)

    fun frames(handle: Long): Int

    fun samplesPlayed(handle: Long): Long

    fun copyFrame(handle: Long, afterGen: Int = -1): NativeMseFrame?
}
