package io.ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** [downscaled] is false when the source already fit the request — nothing was shrunk. */
class DecodedImage(val bitmap: ImageBitmap, val downscaled: Boolean)

/**
 * Decode [bytes] into a bitmap fitting [size] (aspect kept, never upscaled) — the building block
 * for [ImageFetcher] implementations whose data is in memory. Platform decoders scale during/right
 * after decode so the full-size image never stays resident. Throws on undecodable bytes.
 */
suspend fun decodeScaled(bytes: ByteArray, size: IntSize): DecodedImage =
    withContext(Dispatchers.Default) { decodeScaled(bytes, size.width, size.height) }

/**
 * [decodeScaled] straight from a file — for fetchers whose data lives on disk: same clamp
 * semantics, but the compressed data never enters the heap whole on Android ([fileSystem] must be
 * the real one there for the two-pass `BitmapFactory.decodeFile`; any other — e.g. a test fake —
 * falls back to read-bytes + decode). Skia's codec API is bytes-only, so skiko reads the file into
 * memory first.
 */
suspend fun decodeScaledFile(fileSystem: okio.FileSystem, path: okio.Path, size: IntSize): DecodedImage =
    withContext(Dispatchers.IO) { decodeScaledFile(fileSystem, path, size.width, size.height) }

internal expect fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): DecodedImage

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
