package ymusic.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kmpcommon.io.AsyncSink
import kmpcommon.io.AsyncSource
import kotlinx.coroutines.runBlocking
import okio.Buffer
import java.io.InputStream
import java.io.OutputStream

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
internal actual suspend fun decodeSource(source: AsyncSource): ImageBitmap {
    val bitmap = BitmapFactory.decodeStream(AsyncSourceInputStream(source))
        ?: throw IllegalArgumentException("undecodable cached image")
    return bitmap.asImageBitmap()
}

internal actual suspend fun encodePngToSink(bitmap: ImageBitmap, sink: AsyncSink) {
    val out = AsyncSinkOutputStream(sink)
    bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    out.flush()
}

// The runBlocking bridges are nominal unwraps, not real blocking-on-async: the disk cache's
// AsyncSource/AsyncSink wrap synchronous okio file I/O (OkioFileSystem) and never suspend.

private class AsyncSourceInputStream(private val source: AsyncSource) : InputStream() {
    private val buffer = Buffer()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (buffer.exhausted()) {
            if (runBlocking { source.read(buffer, SEGMENT) } == -1L) return -1
        }
        return buffer.read(b, off, minOf(len.toLong(), buffer.size).toInt())
    }
}

private class AsyncSinkOutputStream(private val sink: AsyncSink) : OutputStream() {
    private val buffer = Buffer()

    override fun write(b: Int) {
        buffer.writeByte(b)
        drainIfFull()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
        drainIfFull()
    }

    override fun flush() {
        runBlocking {
            if (buffer.size > 0) sink.write(buffer, buffer.size)
            sink.flush()
        }
    }

    private fun drainIfFull() {
        if (buffer.size >= SEGMENT) runBlocking { sink.write(buffer, buffer.size) }
    }
}

private const val SEGMENT = 64L * 1024
