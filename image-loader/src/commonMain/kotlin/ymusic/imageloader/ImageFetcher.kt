package ymusic.imageloader

/**
 * Resolves a non-http url the loader can't fetch itself to raw image bytes — e.g. album art
 * embedded in a local audio file, extracted via ffmpeg (`art://<path>` or whatever scheme the app
 * defines). Return null when the url isn't yours; the loader then tries its built-in paths
 * (`file://`, then http). Results flow through the normal clamped decode and the memory/scaled
 * caches, so a warm load never re-runs the extraction.
 */
fun interface ImageFetcher {
    suspend fun fetch(url: String): ByteArray?
}
