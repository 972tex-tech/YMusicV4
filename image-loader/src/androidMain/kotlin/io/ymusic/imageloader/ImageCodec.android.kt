package io.ymusic.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer

// inSampleSize decodes at 1/2^n directly — the full-size bitmap is never allocated. The result is
// the smallest power-of-two subsample still covering (or, [fit], still reaching) the target — up to
// 2x the request — the platform's standard efficient-decode recipe.
internal actual fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int, fit: Boolean): DecodedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("undecodable image (${bytes.size} bytes)")
    }
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight, fit)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IllegalArgumentException("undecodable image (${bytes.size} bytes)")
    return DecodedImage(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

internal actual fun decodeScaledFile(
    fileSystem: FileSystem,
    path: Path,
    maxWidth: Int,
    maxHeight: Int,
    fit: Boolean,
): DecodedImage {
    if (fileSystem !== FileSystem.SYSTEM) {
        // Test fakes have no real files for BitmapFactory — read through the fake instead.
        return decodeScaled(fileSystem.read(path) { readByteArray() }, maxWidth, maxHeight, fit)
    }
    val file = path.toString()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("undecodable image file: $path")
    }
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight, fit)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file, options)
        ?: throw IllegalArgumentException("undecodable image file: $path")
    return DecodedImage(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

/** Halving keeps covering while both sides stay >= the box; keeps fitting while either does. */
private fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int, fit: Boolean): Int {
    var sample = 1
    while (true) {
        val w = width / (sample * 2)
        val h = height / (sample * 2)
        val keeps = if (fit) w >= maxWidth || h >= maxHeight else w >= maxWidth && h >= maxHeight
        if (!keeps) return sample
        sample *= 2
    }
}

internal actual fun encodeWebpToSink(bitmap: ImageBitmap, sink: Sink) {
    val buffered = sink.buffer()
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION") // lossy at quality < 100 — the pre-R spelling of WEBP_LOSSY
        Bitmap.CompressFormat.WEBP
    }
    bitmap.asAndroidBitmap().compress(format, SCALED_WEBP_QUALITY, buffered.outputStream())
    buffered.emit()
}
