package ymusic.imageloader

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode [bytes] into a bitmap at most [maxWidth]×[maxHeight] (aspect kept, never upscaled).
 * Platform decoders scale during/right after decode so the full-size image never stays resident.
 * Throws on undecodable bytes.
 */
internal expect fun decodeScaled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageBitmap

/** Encode an already-scaled bitmap as PNG (the scaled-image cache's storage format). */
internal expect fun encodePng(bitmap: ImageBitmap): ByteArray
