package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap

/** [downscaled] is false when the source already fit the request — nothing was shrunk. */
internal class DecodedImage(val bitmap: ImageBitmap, val downscaled: Boolean)

/**
 * Decode [bytes] into a bitmap at most [maxWidth]×[maxHeight] (aspect kept, never upscaled).
 * Platform decoders scale during/right after decode so the full-size image never stays resident.
 * Throws on undecodable bytes.
 */
internal expect fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage

/**
 * [decodeScaled] straight from a file: same clamp semantics, but the compressed data never enters
 * the heap whole on Android ([fileSystem] must be the real one there for the two-pass
 * `BitmapFactory.decodeFile`; any other — e.g. a test fake — falls back to read-bytes + decode).
 * Skia's codec API is bytes-only, so skiko reads the file into memory first.
 */
internal expect fun decodeScaledFile(
    fileSystem: okio.FileSystem,
    path: okio.Path,
    maxWidth: Int,
    maxHeight: Int,
): DecodedImage

/**
 * Encode an already-scaled bitmap as PNG into [sink] (the scaled-image cache's storage format).
 * Android compresses straight into the stream; skiko encodes in memory first (Skia limitation).
 */
internal expect fun encodePngToSink(bitmap: ImageBitmap, sink: okio.Sink)
