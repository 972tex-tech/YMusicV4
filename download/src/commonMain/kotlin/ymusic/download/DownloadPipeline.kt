package ymusic.download

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import okio.FileSystem
import okio.Path
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.sabr.AdaptiveFormat
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

/** A worker was asked to stop mid-run (pause/cancel). Distinct from a failure — no error surfaced. */
internal class DownloadInterrupted : Exception()

/**
 * Executes one [DownloadTask] end to end: pick formats → download the elementary stream(s) via
 * [SabrSession] → transcode/mux with [Ffmpeg] into the requested container → return the final file.
 *
 * Audio elementary streams are already valid container files (a raw itag-140 stream is a playable
 * `.m4a`, itag-251 a playable `.webm`), so audio-only + no-ffmpeg (iOS M4A) downloads straight to the
 * final path with no post-processing. MP3 and video (mux) require [ffmpeg]; when it's null those
 * outputs are rejected (the UI hides them on such platforms).
 */
class DownloadPipeline(
    private val resolver: SabrSpecResolver,
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val ffmpeg: Ffmpeg?,
    private val fileSystem: FileSystem,
    private val workDir: Path,
    private val outputDir: Path,
    private val mediaCache: MediaCache?,
    private val logger: Logger?,
) {

    /** Result of a completed pipeline run: the final file plus the format metadata for library ingest. */
    class Result(val file: Path, val audio: AdaptiveFormat, val video: AdaptiveFormat?)

    suspend fun run(
        task: DownloadTask,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result {
        fileSystem.createDirectories(workDir)
        fileSystem.createDirectories(outputDir)

        onPreparing()
        var spec = resolver.resolve(task.videoId)

        val (audioFmt, videoFmt) = selectFormats(task, spec.formats)
        val finalPath = outputDir / uniqueFileName(task)

        // M4A with no ffmpeg: the raw best-AAC stream IS a playable .m4a — stream straight to the final file.
        if (task.format == DownloadFormat.M4A && ffmpeg == null) {
            onDownloading()
            downloadTracks(spec, audioFmt, null, OkioDownloadFile(fileSystem, finalPath), null, onProgress) {
                spec = resolver.resolve(task.videoId); spec
            }
            return Result(finalPath, audioFmt, null)
        }

        // Everything else stages elementary streams under workDir, then transcodes/muxes into finalPath.
        val audioTmp = workDir / "${task.id}.audio"
        val videoTmp = workDir / "${task.id}.video"
        val audioFile = OkioDownloadFile(fileSystem, audioTmp)
        val videoFile = if (videoFmt != null) OkioDownloadFile(fileSystem, videoTmp) else null

        onDownloading()
        downloadTracks(spec, audioFmt, videoFmt, audioFile, videoFile, onProgress) {
            spec = resolver.resolve(task.videoId); spec
        }

        onProcessing()
        val ff = ffmpeg ?: error("ffmpeg unavailable: ${task.format} needs transcode/mux on this platform")
        val meta = task.tags()
        when (task.format) {
            DownloadFormat.MP3 -> ff.toMp3(audioTmp, finalPath, meta)
            DownloadFormat.M4A -> ff.toM4a(audioTmp, finalPath, copy = audioFmt.mimeType.contains("mp4"), metadata = meta)
            DownloadFormat.VIDEO_WEBM -> ff.muxWebm(videoTmp, audioTmp, finalPath, meta)
            DownloadFormat.VIDEO_MP4 -> ff.muxMp4(videoTmp, audioTmp, finalPath, meta)
        }
        fileSystem.delete(audioTmp, mustExist = false)
        if (videoFmt != null) fileSystem.delete(videoTmp, mustExist = false)
        return Result(finalPath, audioFmt, videoFmt)
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
        audioFile: OkioDownloadFile,
        videoFile: OkioDownloadFile?,
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
