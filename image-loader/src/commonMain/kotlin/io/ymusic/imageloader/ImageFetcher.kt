package io.ymusic.imageloader

import androidx.compose.ui.unit.IntSize
import kmpcommon.uri.Uri

/**
 * One uri-scheme resolver: fetch the data AND decode it to [size] under [scale] — however suits its
 * storage best (a file decode, a capped stream drain, even scale-during-extraction). The loader
 * parses the caller's url once and asks its fetchers in order; the first non-null result wins.
 * Return null when the [uri]'s scheme isn't yours. Everything is a fetcher — http ([networkFetcher]),
 * local files ([fileFetcher]), and app-defined schemes like `art://…` for ffmpeg-extracted album art.
 *
 * Results flow into the loader's memory/scaled caches, so a warm load never re-runs a fetch or
 * extraction. A fetcher that caches on disk derives its own key from [uri] ([imageCacheKey]).
 * Failures are [ImageLoadException]s with a reason (the loader remembers [ImageLoadException.Reason.NotFound]);
 * guarding (byte caps, retries on a vanished cache file) is the fetcher's own responsibility.
 * [decodeScaled]/[decodeScaledFile] are the module's clamped decoders — or write your own.
 */
fun interface ImageFetcher {
    /** [size] never asks for an upscale. Return null when [uri]'s scheme isn't handled here. */
    suspend fun fetch(uri: Uri, size: IntSize, scale: ImageScale): DecodedImage?
}
