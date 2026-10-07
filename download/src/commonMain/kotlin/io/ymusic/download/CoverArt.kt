package io.ymusic.download

import kmpcommon.io.writeAll
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.asyncSource
import okio.buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches the video's largest JPEG thumbnail into a scratch file for ffmpeg to attach as cover art.
 * JPEG only: the shipped ffmpeg has no WebP decoder, and a JPEG is stream-copied into the tag as-is.
 */
internal class CoverArt(
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val storage: DownloadStorage,
    private val logger: Logger?,
) {
    /** The cover scratch, or null when no candidate yields a JPEG — the song then publishes without one. */
    suspend fun fetch(task: DownloadTask): DownloadScratch? {
        val scratch = storage.scratch(task.id, "cover")
        for (url in candidates(task)) {
            scratch.delete()
            try {
                if (download(url, scratch) && scratch.isJpeg()) return scratch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.log(Logger.Level.WARN, "cover art fetch failed: $url", e)
            }
        }
        scratch.delete()
        logger?.log(Logger.Level.WARN, "no JPEG cover art for ${task.videoId}; publishing without one")
        return null
    }

    /** maxresdefault (1280×720) isn't generated for every video; hqdefault (480×360) always is. */
    private fun candidates(task: DownloadTask): List<String> = listOfNotNull(
        "https://i.ytimg.com/vi/${task.videoId}/maxresdefault.jpg",
        task.thumbnailUrl,
        "https://i.ytimg.com/vi/${task.videoId}/hqdefault.jpg",
    ).distinct()

    private suspend fun download(url: String, scratch: DownloadScratch): Boolean {
        val response = client.execute(RequestBuilder().url(url).header("user-agent", userAgent).get().build())
        try {
            if (!response.isSuccessful) return false
            scratch.appendSink().use { sink ->
                sink.writeAll(response.body.asyncSource())
                sink.flush()
            }
            return true
        } finally {
            response.close()
        }
    }

    private fun DownloadScratch.isJpeg(): Boolean = source().buffer().use { source ->
        source.request(2) && source.readShort() == JPEG_SOI
    }

    private companion object {
        const val JPEG_SOI: Short = 0xFFD8.toShort()
    }
}
