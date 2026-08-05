package ymusic.download

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.sabr.AdaptiveFormat
import ymusic.ytmedia.sabr.SabrStreamSpec

/**
 * Resolves the SABR inputs for a video (loads/plays it in a WebView, extracts the player response + PO
 * token). Implemented by the app shell over its `WebViewPlayer`; the download module stays headless.
 */
fun interface SabrSpecResolver {
    suspend fun resolve(videoId: String): SabrStreamSpec
}

/**
 * Executes one [DownloadTask] end to end, over a [DownloadStorage] (no direct FileSystem — so the same
 * pipeline works whether the final file is a `file://` path or an Android `content://` SAF document):
 *
 *  1a. download the elementary stream(s) + captions to scratch files ([YtStreamDownloader]).
 *  1b. (optional) download the thumbnail for cover art — TODO.
 *  2.  mux/convert + embed metadata with [ffmpeg] into a final scratch file.
 *  3.  clean up the elementary scratch files.
 *  4.  publish the final scratch to the destination (returns its uri) — the shell then adds it to the
 *      local library ([DownloadService.onCompleted]).
 *
 * Audio elementary streams are already valid container files, so audio-only + no-ffmpeg (iOS M4A) skips
 * step 2 — the raw stream is published as-is and its metadata lives in the DB task row.
 */
class DownloadPipeline(
    resolver: SabrSpecResolver,
    client: KmpHttpClient,
    userAgent: String,
    private val ffmpeg: Ffmpeg?,
    private val storage: DownloadStorage,
    mediaCache: MediaCache?,
    private val logger: Logger?,
) {

    private val downloader = YtStreamDownloader(resolver, client, userAgent, storage, mediaCache, logger)

    /** A completed run: the published file's uri + byte size, and the formats (for library ingest). */
    class Result(val uri: String, val bytes: Long, val audio: AdaptiveFormat, val video: AdaptiveFormat?)

    suspend fun run(
        task: DownloadTask,
        captions: CaptionPreference,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result {
        // 1. download the elementary streams (+ captions) to scratch files.
        val streams = downloader.download(task, captions, onPreparing, onDownloading, onProgress)
        val audioFmt = streams.audio.format
        val videoFmt = streams.video?.format
        val fileName = uniqueFileName(task)

        // 1b. optional thumbnail download for cover art — TODO.

        onProcessing()

        // No-ffmpeg M4A (iOS): the raw best-AAC stream IS a playable .m4a — publish it as-is, no step 2
        // (metadata lives in the DB task row).
        if (task.format == DownloadFormat.M4A && ffmpeg == null) {
            val bytes = streams.audio.scratch.length()
            val uri = storage.publish(streams.audio.scratch, fileName)
            cleanupCaptions(streams) // TODO: sidecar/mux the downloaded captions (next pipeline step)
            return Result(uri, bytes, audioFmt, null)
        }

        // 2. mux/convert + metadata into the final scratch.
        val ff = ffmpeg ?: error("ffmpeg unavailable: ${task.format} needs transcode/mux on this platform")
        val audio = streams.audio.scratch
        val video = streams.video?.scratch
        val out = storage.scratch("${task.id}.out")
        val meta = task.tags()
        when (task.format) {
            DownloadFormat.MP3 -> ff.toMp3(audio.path, out.path, meta)
            DownloadFormat.M4A -> ff.toM4a(audio.path, out.path, copy = audioFmt.mimeType.contains("mp4"), metadata = meta)
            DownloadFormat.VIDEO_WEBM -> ff.muxWebm(video!!.path, audio.path, out.path, meta)
            DownloadFormat.VIDEO_MP4 -> ff.muxMp4(video!!.path, audio.path, out.path, meta)
        }
        val bytes = out.length()

        // 3. clean up the elementary scratch files.
        audio.delete()
        video?.delete()
        cleanupCaptions(streams) // TODO: sidecar/mux the downloaded captions (next pipeline step)

        // 4. publish the final file; the caller adds it to the library.
        return Result(storage.publish(out, fileName), bytes, audioFmt, videoFmt)
    }

    // Captions are downloaded by [YtStreamDownloader] but not yet consumed — the mux/sidecar step is TODO,
    // so for now they're cleaned up here rather than left as scratch litter.
    private fun cleanupCaptions(streams: DownloadedStreams) {
        streams.captions.forEach { it.scratch.delete() }
    }

    /** Container tags embedded into the finished file. The videoId goes in the standard `composer` field
     *  — it survives in both ID3 (TCOM) and MP4 (©wrt), and the app's ffmpeg metadata parser reads it
     *  (`MetadataMap.composer`), unlike a custom atom MP4 hides. Title always; artist when we have one
     *  (album is not modelled yet — add it here once the request carries one). */
    private fun DownloadTask.tags(): List<Pair<String, String>> = buildList {
        add("title" to title)
        artist?.let { add("artist" to it) }
        add("composer" to videoId)
    }

    /** `Title-videoId.ext`, sanitized to a safe filename. The videoId keeps it unique across same-title videos. */
    private fun uniqueFileName(task: DownloadTask): String {
        val base = task.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim()
            .take(80)
            .ifBlank { task.videoId }
        return "$base-${task.videoId}.${task.format.fileExtension}"
    }
}
