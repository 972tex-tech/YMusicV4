package io.ymusic.download

import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SegmentIndex
import io.ymusic.ytmedia.sabr.AdaptiveFormat
import io.ymusic.ytmedia.sabr.CaptionTrack
import io.ymusic.ytmedia.sabr.DownloadFile
import io.ymusic.ytmedia.sabr.SabrPoTokenExpired
import io.ymusic.ytmedia.sabr.SabrProgress
import io.ymusic.ytmedia.sabr.SabrSession
import io.ymusic.ytmedia.sabr.SabrStreamSpec
import io.ymusic.ytmedia.sabr.SabrTrack
import kmpcommon.error.TechnicalException
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.byteString
import okio.IOException
import okio.use

/** Which caption tracks to fetch, distilled from the user's settings (captured by the caller). */
class CaptionPreference(val languages: Set<String>, val includeDefault: Boolean) {
    companion object {
        val None = CaptionPreference(emptySet(), false)
    }
}

/** Caption tracks to fetch for [format]: none for MP3 — it has no way to carry a subtitle/text track, so
 *  downloading them would be wasted — otherwise the preference-driven [selectCaptions]. */
internal fun captionsToDownload(
    format: DownloadFormat,
    tracks: List<CaptionTrack>,
    pref: CaptionPreference,
): List<CaptionTrack> = if (format == DownloadFormat.MP3) emptyList() else selectCaptions(tracks, pref)

/**
 * The caption tracks to download: those whose language matches a preference (exact or base-language,
 * e.g. `pt` matches `pt-BR`). If none match and [CaptionPreference.includeDefault] is set, fall back to
 * the video's default track (or the first non-ASR / first available). Empty when nothing applies.
 */
internal fun selectCaptions(tracks: List<CaptionTrack>, pref: CaptionPreference): List<CaptionTrack> {
    if (tracks.isEmpty()) return emptyList()
    val wanted = tracks.filter {
        it.languageCode in pref.languages || it.languageCode.substringBefore('-') in pref.languages
    }
    if (wanted.isNotEmpty()) return wanted
    return if (pref.includeDefault) {
        listOfNotNull(tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull { !it.isAsr } ?: tracks.first())
    } else {
        emptyList()
    }
}

/** One downloaded elementary media stream: its selected [format] and the scratch file it landed in. */
class DownloadedStream(val format: AdaptiveFormat, val scratch: DownloadScratch)

/** One downloaded caption track: the source metadata plus the scratch file (a `.vtt` document). */
class DownloadedCaption(
    val languageCode: String,
    val name: String,
    val isDefault: Boolean,
    val scratch: DownloadScratch,
)

/** Everything [YtStreamDownloader] hands to the next pipeline step (mux/convert): the files on disk. */
class DownloadedStreams(
    val audio: DownloadedStream,
    val video: DownloadedStream?,
    val captions: List<DownloadedCaption>,
)

/**
 * The download step of the pipeline: takes a queued [DownloadTask] and produces the elementary files
 * (audio, optional video, captions) the mux/convert step then assembles. It:
 *
 *  1. resolves the SABR inputs (player response + PO token) for the videoId — via [SabrSpecResolver],
 *     which drives the WebView.
 *  2. picks the concrete audio/video [AdaptiveFormat]s for the task and the caption tracks for [captions].
 *  3. streams each audio/video track to its own scratch file over SABR — resumable (the scratch's
 *     on-disk length is the resume point), re-attesting once if the PO token is rejected mid-stream.
 *  4. fetches each selected caption over plain HTTP (its baseUrl is already signature-decoded + pot-bound
 *     by webview-js) into a scratch file.
 *
 * Scratch files live in app-private storage ([DownloadStorage.scratch]) — unrelated to the user's
 * download-location setting, which only governs where the *final* file is published.
 */
class YtStreamDownloader(
    private val resolver: SabrSpecResolver,
    private val client: KmpHttpClient,
    private val userAgent: String,
    private val storage: DownloadStorage,
    private val mediaCache: MediaCache?,
    /** Persistent segment index — records each downloaded segment's byte range and seeds the SABR resume
     *  cursor from the on-disk file length. Null → no resume (re-downloads from zero on pause/resume). */
    private val segmentIndex: SegmentIndex?,
    logger: Logger?,
) {

    private val log = logger?.fork("download")?.fork("streams")

    suspend fun download(
        task: DownloadTask,
        captions: CaptionPreference,
        onPreparing: () -> Unit,
        onDownloading: () -> Unit,
        onProgress: (done: Long, total: Long) -> Unit,
    ): DownloadedStreams {
        onPreparing()
        var spec = resolveSpec(task.videoId)
        val (audioFmt, videoFmt) = selectFormats(task, spec.formats)
        val captionPicks = captionsToDownload(task.format, spec.captionTracks, captions)

        val audioScratch = storage.scratch(task.id, "audio")
        val videoScratch = if (videoFmt != null) storage.scratch(task.id, "video") else null

        onDownloading()
        downloadTracks(
            spec, audioFmt, videoFmt, audioScratch, videoScratch, onProgress,
            reAttest = { spec = resolveSpec(task.videoId); spec },
        )

        // Captions are KB-scale; fetched after the media, whole (no range-resume), not folded into progress.
        // Best-effort: a caption that fails to download is logged and skipped — it must not fail the media.
        val captionFiles = captionPicks.mapNotNull { track ->
            runCatching { downloadCaption(task.id, track) }
                .onFailure { log?.log(Logger.Level.WARN, "caption ${track.languageCode} failed: ${it.message}") }
                .getOrNull()
        }

        return DownloadedStreams(
            audio = DownloadedStream(audioFmt, audioScratch),
            video = videoFmt?.let { DownloadedStream(it, videoScratch!!) },
            captions = captionFiles,
        )
    }

    private suspend fun resolveSpec(videoId: String): SabrStreamSpec =
        try {
            resolver.resolve(videoId)
        } catch (e: IllegalStateException) {
            // SabrSpecBuilder throws this when the response carries no serverAbrStreamingUrl.
            if (e.message?.contains("serverAbrStreamingUrl") == true) {
                throw TechnicalException("E70010", "no SABR stream for $videoId; progressive download is not yet supported", cause = e)
            }
            throw e
        }

    /** Choose the audio (and, for video outputs, video) [AdaptiveFormat]s for the task. */
    private fun selectFormats(task: DownloadTask, formats: List<AdaptiveFormat>): Pair<AdaptiveFormat, AdaptiveFormat?> {
        val preferAac = task.format == DownloadFormat.M4A || task.format == DownloadFormat.VIDEO_MP4
        val audio = FormatSelection.bestAudio(formats, preferAac = preferAac)
            ?: throw TechnicalException("E70011", "no audio format for ${task.videoId}")
        val video = if (task.format.kind == DownloadKind.VIDEO) {
            FormatSelection.videoNearest(formats, task.requestedHeight, task.format)
                ?: throw TechnicalException("E70012", "no video format for ${task.videoId}")
        } else {
            null
        }
        return audio to video
    }

    /** GET the caption's WebVTT into a scratch file. The baseUrl is already GET-ready (sig/n + pot). */
    private suspend fun downloadCaption(taskId: String, track: CaptionTrack): DownloadedCaption {
        val scratch = storage.scratch(taskId, "cc.${track.languageCode}.vtt")
        scratch.delete() // tiny file — always refetch whole rather than resume a partial
        val url = track.baseUrl + (if ('?' in track.baseUrl) "&" else "?") + "fmt=vtt"
        val response = client.execute(RequestBuilder().url(url).header("user-agent", userAgent).get().build())
        try {
            if (!response.isSuccessful) throw IOException("caption ${track.languageCode} HTTP ${response.code}")
            val bytes = response.body.byteString()
            scratch.appendSink().use { sink -> sink.write(bytes); sink.flush() }
        } finally {
            response.close()
        }
        return DownloadedCaption(track.languageCode, track.name, track.isDefault, scratch)
    }

    /**
     * Run the SABR download, re-priming a fresh spec once if the PO token is rejected mid-stream. Each
     * session records its segments in [segmentIndex] and seeds its resume cursor from the on-disk file
     * length via that index — so pause/resume and re-attest continue from the last segment (see
     * [SabrSession.download]), rather than re-fetching from zero.
     */
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
                SabrSession(log = { line -> log.debug { line } }).download(
                    client = client,
                    userAgent = userAgent,
                    spec = current,
                    audio = SabrTrack(audioFmt, audioFile),
                    video = videoFmt?.let { SabrTrack(it, videoFile!!) },
                    progress = progress,
                    mediaCache = mediaCache,
                    cacheReadOnly = true, // reuse cached playback bytes, but never write into the player cache
                    segmentIndex = segmentIndex,
                )
                return
            } catch (e: SabrPoTokenExpired) {
                if (reAttested) throw e
                reAttested = true
                log?.log(Logger.Level.WARN, "PO token rejected, re-attesting: ${e.message}")
                current = reAttest()
            }
        }
    }
}
