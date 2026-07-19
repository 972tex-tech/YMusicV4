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
 * Decode an **already-scaled** image read from [source]. Android decodes incrementally off the
 * stream — the compressed data is never fully resident; Skia's codec API is bytes-only, so the
 * skiko actual drains first.
 *
 * WARNING: there is NO size clamp here — the bitmap allocates at the image's full dimensions.
 * Feeding an original (unscaled) image is an OOM waiting to happen; originals go through
 * [decodeScaled], only the scaled-cache's own pre-shrunk entries come through here.
 */
internal expect fun decodeSource(source: okio.Source): ImageBitmap

/**
 * Encode an already-scaled bitmap as PNG into [sink] (the scaled-image cache's storage format).
 * Android compresses straight into the stream; skiko encodes in memory first (Skia limitation).
 */
internal expect fun encodePngToSink(bitmap: ImageBitmap, sink: okio.Sink)
