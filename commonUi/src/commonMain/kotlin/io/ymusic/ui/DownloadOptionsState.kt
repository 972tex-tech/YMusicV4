package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ymusic.download.CaptionChoice
import io.ymusic.download.CaptionOption
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadOptions
import io.ymusic.download.DownloadRequest
import io.ymusic.download.DownloadService
import io.ymusic.download.VideoOption
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_size_gb
import io.ymusic.ui.resources.download_size_mb
import kmpcommon.error.codedError
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/** The last picks, so the next download opens where this one left off (for the app session). */
private object LastDownloadChoice {
    var audioOnly = true
    var audioFormat = DownloadFormat.M4A
    var container = DownloadFormat.VIDEO_MP4
    var height = 1080
}

/**
 * The download options dialog's model, shared by the mobile sheet and the desktop dialog: the probed
 * [options] for [video] and the user's picks, turned into a [DownloadRequest] by [request].
 */
@Stable
internal class DownloadOptionsState(
    val video: YtFeedItem,
    private val downloads: DownloadService,
    private val preferredCaptionLanguages: Set<String>,
    private val includeDefaultCaption: Boolean,
) {
    var options by mutableStateOf<DownloadOptions?>(null)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set

    var audioOnly by mutableStateOf(LastDownloadChoice.audioOnly)
    var audioFormat by mutableStateOf(LastDownloadChoice.audioFormat)
    var container by mutableStateOf(LastDownloadChoice.container)
    var height by mutableStateOf<Int?>(null)
    var audioTrackId by mutableStateOf<String?>(null)
    var captions by mutableStateOf<Set<String>>(emptySet())
    var fileName by mutableStateOf(DownloadFileNames.fromTitle(video.title).ifEmpty { video.id })

    val hasVideo: Boolean get() = options?.video?.isNotEmpty() == true

    val format: DownloadFormat get() = if (audioOnly || !hasVideo) audioFormat else container

    val extension: String get() = "." + format.fileExtension

    /** Captions are muxed into the file: not into MP3 (no subtitle tracks), and not without ffmpeg (iOS). */
    val captionsSupported: Boolean get() = format != DownloadFormat.MP3 && downloads.ffmpegAvailable

    val selectedVideo: VideoOption? get() = options?.video?.firstOrNull { it.height == height }

    val selectedBytes: Long?
        get() {
            val opts = options ?: return null
            return if (format.kind == io.ymusic.download.DownloadKind.AUDIO) {
                opts.audioBytes(format)
            } else {
                selectedVideo?.let { if (format == DownloadFormat.VIDEO_WEBM) it.webmBytes else it.mp4Bytes }
            }
        }

    suspend fun load(log: Logger?) {
        errorCode = null
        try {
            val probed = downloads.probe(video.id)
            if (probed.audio.none { it.format == audioFormat }) audioFormat = probed.audio.first().format
            height = pickHeight(probed.video, LastDownloadChoice.height)
            audioTrackId = probed.audioTracks.firstOrNull { it.isDefault }?.id ?: probed.audioTracks.firstOrNull()?.id
            captions = defaultCaptions(probed.captions)
            options = probed
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log?.fork("download-options", mapOf("videoId" to video.id))?.log(Logger.Level.WARN, "probe failed", e)
            errorCode = e.codedError()?.code ?: "E70043"
        }
    }

    fun toggleCaption(key: String) {
        captions = if (key in captions) captions - key else captions + key
    }

    fun request(): DownloadRequest {
        LastDownloadChoice.audioOnly = audioOnly
        LastDownloadChoice.audioFormat = audioFormat
        LastDownloadChoice.container = container
        height?.let { LastDownloadChoice.height = it }
        val opts = options
        return DownloadRequest(
            videoId = video.id,
            title = video.title,
            artist = null,
            thumbnailUrl = video.thumbs.firstOrNull()?.url,
            format = format,
            requestedHeight = if (format.kind == io.ymusic.download.DownloadKind.VIDEO) height else null,
            fileName = DownloadFileNames.finalize(fileName).ifEmpty { null },
            audioTrackId = audioTrackId?.takeIf { opts?.audioTracks?.isNotEmpty() == true },
            captions = CaptionChoice(
                if (captionsSupported) opts?.captions?.map { it.key }?.filter { it in captions }.orEmpty() else emptyList(),
            ),
        )
    }

    /** The highest quality at or below the last pick, else the lowest offered. */
    private fun pickHeight(video: List<VideoOption>, preferred: Int): Int? =
        video.firstOrNull { it.height <= preferred }?.height ?: video.lastOrNull()?.height

    /** The settings-preferred languages (manual tracks before auto-generated); the video's default track
     *  when none match and the settings ask for it. */
    private fun defaultCaptions(tracks: List<CaptionOption>): Set<String> {
        val wanted = tracks.filter { it.languageCode in preferredCaptionLanguages || it.languageCode.substringBefore('-') in preferredCaptionLanguages }
        val picked = wanted.filter { !it.isAsr }.ifEmpty { wanted }
        if (picked.isNotEmpty()) return picked.map { it.key }.toSet()
        if (!includeDefaultCaption) return emptySet()
        val fallback = tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull { !it.isAsr } ?: tracks.firstOrNull()
        return setOfNotNull(fallback?.key)
    }
}

/** "4K", "1080p60" — the quality name a tile/menu shows. */
internal fun VideoOption.label(): String {
    val name = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        else -> "${height}p"
    }
    return if (fps >= 48) "$name$fps" else name
}

/** A download size as "4.1 MB" / "92 MB" / "1.2 GB". */
@Composable
internal fun formatDownloadSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb >= 1000 -> stringResource(Res.string.download_size_gb, oneDecimal(mb / 1000))
        mb >= 10 -> stringResource(Res.string.download_size_mb, mb.toLong().toString())
        else -> stringResource(Res.string.download_size_mb, oneDecimal(mb))
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
