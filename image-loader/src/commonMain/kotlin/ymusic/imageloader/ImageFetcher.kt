package ymusic.imageloader

/**
 * Resolves a non-http url the loader can't fetch itself to an image byte stream — e.g. album art
 * embedded in a local audio file, extracted via ffmpeg (`art://<path>` or whatever scheme the app
 * defines). Return null when the url isn't yours; the loader then tries its built-in paths
 * (`file://`, then http). The loader consumes and closes the source, applies its byte cap while
 * reading, and runs the normal clamped decode + memory/scaled caches — a warm load never re-runs
 * the extraction. An implementation that only has bytes wraps them: `Buffer().write(bytes)`.
 */
fun interface ImageFetcher {
    suspend fun fetch(url: String): okio.Source?
}
