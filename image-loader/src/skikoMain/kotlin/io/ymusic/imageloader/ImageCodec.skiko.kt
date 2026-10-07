package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import okio.buffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// `use` here is skiko's (impl.use): kotlin.io.use needs AutoCloseable, absent from Managed on native.
// Skia has no subsampled decode; decode full then immediately draw down into a target-size raster —
// the full-size image is closed eagerly (Cleaner-freed native pixels would otherwise pile up during
// a fast scroll), only the scaled bitmap stays resident.
internal actual fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int, fit: Boolean): DecodedImage {
    Image.makeFromEncoded(bytes).use { image ->
        val sx = maxWidth.toFloat() / image.width
        val sy = maxHeight.toFloat() / image.height
        val scale = if (fit) min(sx, sy) else max(sx, sy)
        // toComposeImageBitmap() copies the pixels, so the source is safe to close in both branches.
        if (scale >= 1f) return DecodedImage(image.toComposeImageBitmap(), image.width, image.height)
        val w = max(1, (image.width * scale).roundToInt())
        val h = max(1, (image.height * scale).roundToInt())
        val bitmap = Bitmap()
        try {
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
            Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
            }
        } catch (t: Throwable) {
            bitmap.close()
            throw t
        }
        bitmap.setImmutable()
        // asComposeImageBitmap wraps WITHOUT copying — the bitmap must stay open.
        return DecodedImage(bitmap.asComposeImageBitmap(), image.width, image.height)
    }
}

// Skia's codec API is bytes-only (no incremental decode from a file exposed by skiko) — the
// compressed data is read into memory, then decoded through the normal clamped path.
internal actual fun decodeScaledFile(
    fileSystem: okio.FileSystem,
    path: okio.Path,
    maxWidth: Int,
    maxHeight: Int,
    fit: Boolean,
): DecodedImage = decodeScaled(fileSystem.read(path) { readByteArray() }, maxWidth, maxHeight, fit)

internal actual fun encodeWebpToSink(bitmap: ImageBitmap, sink: okio.Sink) {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
        val data = image.encodeToData(EncodedImageFormat.WEBP, SCALED_WEBP_QUALITY)
            ?: throw IllegalStateException("WebP encode failed (${bitmap.width}x${bitmap.height})")
        val buffer = okio.Buffer()
        data.use { buffer.write(it.bytes) }
        sink.write(buffer, buffer.size)
    }
}
