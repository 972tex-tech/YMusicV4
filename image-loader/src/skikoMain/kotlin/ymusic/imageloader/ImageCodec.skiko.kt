package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Skia has no subsampled decode; decode full then immediately draw down into a target-size raster —
// the full-size image is released right after (only the scaled bitmap stays resident).
internal actual fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageBitmap {
    val image = Image.makeFromEncoded(bytes)
    val scale = min(maxWidth.toFloat() / image.width, maxHeight.toFloat() / image.height)
    if (scale >= 1f) return image.toComposeImageBitmap()
    val w = max(1, (image.width * scale).roundToInt())
    val h = max(1, (image.height * scale).roundToInt())
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
    Canvas(bitmap).drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(w.toFloat(), h.toFloat()),
        SamplingMode.LINEAR,
        null,
        true,
    )
    image.close()
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

internal actual fun encodePng(bitmap: ImageBitmap): ByteArray {
    val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
    val data = image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("PNG encode failed (${bitmap.width}x${bitmap.height})")
    return data.bytes
}
