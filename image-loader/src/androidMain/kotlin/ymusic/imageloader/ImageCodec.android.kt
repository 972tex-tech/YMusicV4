package ymusic.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import okio.Sink
import okio.Source
import okio.buffer

// inSampleSize decodes at 1/2^n directly — the full-size bitmap is never allocated. The result is
// the smallest power-of-two subsample still >= the target (up to 2x the request), the platform's
// standard efficient-decode recipe.
internal actual fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("undecodable image (${bytes.size} bytes)")
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IllegalArgumentException("undecodable image (${bytes.size} bytes)")
    return DecodedImage(bitmap.asImageBitmap(), downscaled = sample > 1)
}

// Cached entries are pre-scaled, so a single-pass stream decode is enough (no bounds/sample pass);
// the compressed data streams through in segments, never fully resident.
internal actual fun decodeSource(source: Source): ImageBitmap {
    val bitmap = BitmapFactory.decodeStream(source.buffer().inputStream())
        ?: throw IllegalArgumentException("undecodable cached image")
    return bitmap.asImageBitmap()
}

internal actual fun encodePngToSink(bitmap: ImageBitmap, sink: Sink) {
    val buffered = sink.buffer()
    bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, buffered.outputStream())
    buffered.emit()
}
