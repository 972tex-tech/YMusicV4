package io.ymusic.download

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import kmpcommon.error.TechnicalException
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient

/**
 * Resolves the SABR inputs for a video (player response + PO token). Implemented by the app shell
 * over its `YtEngine`; the download module stays headless.
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
    segmentIndex: SegmentIndex?,
    private val logger: Logger?,
) {

    private val downloader = YtStreamDownloader(resolver, client, userAgent, storage, mediaCache, segmentIndex, logger)

    /** A completed run: the published file's uri + byte size, and the formats (for library ingest). */
    class Result(val uri: String, val bytes: Long, val audio: AdaptiveFormat, val video: AdaptiveFormat?)

    suspend fun run(
        task: DownloadTask,
        captions: CaptionPreference,
        destinationFolder: String,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProcessing: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result {
        // 1. download the elementary streams (+ captions) to scratch files.
        val streams = downloader.download(task, captions, onPreparing, onDownloading, onProgress)
        val audioFmt = streams.audio.format
        val videoFmt = streams.video?.format
        val base = baseName(task)
        val fileName = "$base.${task.format.fileExtension}"

        // 1b. optional thumbnail download for cover art — TODO.

        onProcessing()

        // No-ffmpeg M4A (iOS): the raw best-AAC stream IS a playable .m4a — publish it as-is, no step 2
        // (metadata lives in the DB task row). Captions can't be muxed without ffmpeg here, so drop them.
        if (task.format == DownloadFormat.M4A && ffmpeg == null) {
            val bytes = streams.audio.scratch.length()
            val uri = storage.publish(streams.audio.scratch, fileName, destinationFolder)
            storage.deleteScratch(task.id)
            return Result(uri, bytes, audioFmt, null)
        }

        // 2. mux/convert + metadata into the final scratch, reporting ffmpeg's output-time progress. The
        // media duration bounds it; we map output-time → the same done/total the UI's bar reads. Start at 0
        // so the bar refills for the processing phase rather than jumping from the download's 100%.
        val ff = ffmpeg ?: throw TechnicalException(
            "E70020", "ffmpeg unavailable: ${task.format} needs transcode/mux on this platform",
        )
        val durationMs = maxOf(audioFmt.approxDurationMs, videoFmt?.approxDurationMs ?: 0).toLong()
        val onFfProgress: ((Long) -> Unit)? = if (durationMs > 0) {
            { us -> onProgress((us / 1000).coerceIn(0, durationMs), durationMs) }
        } else {
            null
        }
        if (durationMs > 0) onProgress(0, durationMs)

        val audio = streams.audio.scratch
        val video = streams.video?.scratch
        val out = storage.scratch(task.id, "out")
        val meta = task.tags()
        val subs = streams.captions.map { SubtitleInput(it.scratch.path, it.languageCode, it.name) }
        when (task.format) {
            DownloadFormat.MP3 -> ff.toMp3(audio.path, out.path, meta, onFfProgress) // MP3 carries no captions
            DownloadFormat.M4A -> ff.toM4a(audio.path, out.path, copy = audioFmt.mimeType.contains("mp4"), captions = subs, metadata = meta, onProgressUs = onFfProgress)
            DownloadFormat.VIDEO_WEBM -> ff.muxWebm(video!!.path, audio.path, out.path, subs, meta, onFfProgress)
            DownloadFormat.VIDEO_MP4 -> ff.muxMp4(video!!.path, audio.path, out.path, subs, meta, onFfProgress)
        }
        val bytes = out.length()

        // 3. publish the final file (moved out of the scratch folder), then drop the task's whole scratch
        //    folder — elementary streams + captions in one delete.
        val uri = storage.publish(out, fileName, destinationFolder)
        storage.deleteScratch(task.id)
        return Result(uri, bytes, audioFmt, videoFmt)
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

    /** `Title-videoId`, sanitized — the shared stem for the media file and its caption sidecars. The
     *  videoId keeps it unique across same-title videos. Callers append the extension. */
    private fun baseName(task: DownloadTask): String {
        val title = task.title.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim()
            .take(80)
            .ifBlank { task.videoId }
        return "$title-${task.videoId}"
    }
}
