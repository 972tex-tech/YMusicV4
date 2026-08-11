package io.ymusic.imageloader

import android.app.ActivityManager
import android.content.Context

/**
 * Device-class default for [ImageLoader]'s memory cache: a slice of the app's heap class —
 * ~20% normally, ~12% on a low-RAM device (the same sizing family Coil/Glide use).
 */
fun defaultMemoryCacheBudget(context: Context): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val heapBytes = am.memoryClass.toLong() * 1024 * 1024
    return (heapBytes / if (am.isLowRamDevice) 8 else 5)
        .coerceIn(16L * 1024 * 1024, 256L * 1024 * 1024)
}
