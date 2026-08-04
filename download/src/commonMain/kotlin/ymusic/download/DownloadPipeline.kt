package ymusic.download

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.sabr.AdaptiveFormat
import ymusic.ytmedia.sabr.DownloadFile
import ymusic.ytmedia.sabr.SabrPoTokenExpired
import ymusic.ytmedia.sabr.SabrProgress
import ymusic.ytmedia.sabr.SabrSession
import ymusic.ytmedia.sabr.SabrStreamSpec
import ymusic.ytmedia.sabr.SabrTrack

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
 *  1a. download the elementary stream(s) to scratch files (via [SabrSession]; a ProgressiveDownloader for
 *      direct-url streams is TODO). Pause/resume rides the scratch's on-disk length.
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
    private val resolver: SabrSpecResolver,
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val ffmpeg: Ffmpeg?,
    private val storage: DownloadStorage,
    private val mediaCache: MediaCache?,
    private val logger: Logger?,
) {

    /** A completed run: the published file's uri + byte size, and the formats (for library ingest). */
    class Result(val uri: String, val bytes: Long, val audio: AdaptiveFormat, val video: AdaptiveFormat?)

    suspend fun run(
        task: DownloadTask,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result {
        onPreparing()
        var spec = resolver.resolve(task.videoId)
        val (audioFmt, videoFmt) = selectFormats(task, spec.formats)
        val fileName = uniqueFileName(task)
        val reAttest: suspend () -> SabrStreamSpec = { spec = resolver.resolve(task.videoId); spec }

        // No-ffmpeg M4A (iOS): the raw best-AAC stream IS a playable .m4a — download it straight into the
        // final scratch and publish, no step 2 (metadata lives in the DB task row).
        if (task.format == DownloadFormat.M4A && ffmpeg == null) {
            val out = storage.scratch("${task.id}.out")
            onDownloading()
            downloadTracks(spec, audioFmt, null, out, null, onProgress, reAttest)
            onProcessing()
            val bytes = out.length()
            return Result(storage.publish(out, fileName), bytes, audioFmt, null) // steps 3+4 (nothing to clean)
        }

        // 1a. download the elementary streams.
        val audio = storage.scratch("${task.id}.audio")
        val video = if (videoFmt != null) storage.scratch("${task.id}.video") else null
        onDownloading()
        downloadTracks(spec, audioFmt, videoFmt, audio, video, onProgress, reAttest)

        // 1b. optional thumbnail download for cover art — TODO.

        // 2. mux/convert + metadata into the final scratch.
        onProcessing()
        val ff = ffmpeg ?: error("ffmpeg unavailable: ${task.format} needs transcode/mux on this platform")
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

        // 4. publish the final file; the caller adds it to the library.
        return Result(storage.publish(out, fileName), bytes, audioFmt, videoFmt)
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

    /** Choose the audio (and, for video outputs, video) [AdaptiveFormat]s for the task. */
    private fun selectFormats(task: DownloadTask, formats: List<AdaptiveFormat>): Pair<AdaptiveFormat, AdaptiveFormat?> {
        val preferAac = task.format == DownloadFormat.M4A || task.format == DownloadFormat.VIDEO_MP4
        val audio = FormatSelection.bestAudio(formats, preferAac = preferAac)
            ?: error("no audio format for ${task.videoId}")
        val video = if (task.format.kind == DownloadKind.VIDEO) {
            FormatSelection.videoNearest(formats, task.requestedHeight, task.format)
                ?: error("no video format for ${task.videoId}")
        } else {
            null
        }
        return audio to video
    }

    /** Run the SABR download, re-priming a fresh spec once if the PO token is rejected mid-stream. */
    private suspend fun downloadTracks(
        spec: SabrStreamSpec,
        audioFmt: AdaptiveFormat,
        videoFmt: AdaptiveFormat?,
        audioFile: DownloadFile,
        videoFile: DownloadFile?,
        onProgress: (Long, Long) -> Unit,
        reAttest: suspend () -> SabrStreamSpec,
    ) {
        val progress = SabrProgress { aDone, aLen, vDone, vLen -> onProgress(aDone + vDone, aLen + vLen) }
        var current = spec
        var reAttested = false
        while (true) {
            try {
                SabrSession(log = { logger?.log(Logger.Level.DEBUG, LOG_CTX, it) }).download(
                    client = client,
                    userAgent = userAgent,
                    spec = current,
                    audio = SabrTrack(audioFmt, audioFile),
                    video = videoFmt?.let { SabrTrack(it, videoFile!!) },
                    progress = progress,
                    mediaCache = mediaCache,
                )
                return
            } catch (e: SabrPoTokenExpired) {
                if (reAttested) throw e
                reAttested = true
                logger?.log(Logger.Level.WARN, LOG_CTX, "PO token rejected, re-attesting: ${e.message}")
                current = reAttest()
            }
        }
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

    private companion object {
        val LOG_CTX = listOf("download", "pipeline")
    }
}
