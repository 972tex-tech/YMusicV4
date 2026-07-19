package ymusic.imageloader

import androidx.compose.ui.unit.IntSize

/**
 * One url scheme resolver: fetch the data AND decode it to fit [IntSize] — however suits its
 * storage best (a file decode, a capped stream drain, even scale-during-extraction). The loader
 * asks its fetchers in order and the first non-null result wins; return null when the url isn't
 * yours. Everything is a fetcher — http ([networkFetcher]), local files ([fileFetcher]), and
 * app-defined schemes like `art://<path>` for ffmpeg-extracted embedded album art.
 *
 * Results flow into the loader's memory/scaled caches, so a warm load never re-runs a fetch or
 * extraction. Guarding (byte caps, retries on a vanished cache file) is the fetcher's own
 * responsibility. [decodeScaled]/[decodeScaledFile] are the module's clamped decoders — or write
 * your own.
 */
fun interface ImageFetcher {
    /** [cacheKey] is the caller's stable identity for [url] (strips rotating params) — fetchers
     *  that cache on disk key by it; others ignore it. [size] never asks for an upscale. */
    suspend fun fetch(url: String, cacheKey: String, size: IntSize): DecodedImage?
}
