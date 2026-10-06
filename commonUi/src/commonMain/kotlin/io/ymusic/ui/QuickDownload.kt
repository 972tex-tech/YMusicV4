package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ymusic.download.CaptionChoice
import io.ymusic.download.DownloadFileNames
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.download.DownloadRequest
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.download_added
import org.jetbrains.compose.resources.stringResource

/**
 * The Quick download preset — picks saved from the options dialog, applied to any video without asking.
 * Languages, not tracks: each download resolves them against its own player response (the nearest
 * [height], the dubbed track in [audioLanguage], the caption in [captionLanguage] — else the defaults).
 * [captionLanguage] null means captions off. Lives in memory for the app run only ([AppGraph.quickDownload]).
 */
data class QuickDownloadPreset(
    val format: DownloadFormat,
    val height: Int?,
    val audioLanguage: String?,
    val captionLanguage: String?,
) {
    fun request(video: YtFeedItem): DownloadRequest = DownloadRequest(
        videoId = video.id,
        title = video.title,
        artist = null,
        thumbnailUrl = video.thumbs.firstOrNull()?.url,
        format = format,
        requestedHeight = if (format.kind == DownloadKind.VIDEO) height else null,
        fileName = DownloadFileNames.fromTitle(video.title).ifEmpty { null },
        captions = if (captionLanguage == null) CaptionChoice(emptyList()) else null,
        audioLanguage = audioLanguage,
        captionLanguage = captionLanguage,
    )

    /** "MP3", "MP4 · 1080p". */
    fun label(): String {
        val name = format.displayName()
        if (format.kind != DownloadKind.VIDEO || height == null) return name
        val quality = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            else -> "${height}p"
        }
        return "$name · $quality"
    }
}

/**
 * Runs a Quick download for the [pending] video: straight into the download folder when one is set, else
 * through the folder picker first (a cancel drops the download). No options dialog.
 */
@Composable
internal fun QuickDownloadHost(graph: AppGraph, pending: YtFeedItem?, onDone: () -> Unit) {
    val downloads = graph.downloads ?: return
    val video = pending ?: return
    val preset = graph.quickDownload.collectAsState().value
    val snackbar = LocalSnackbar.current
    val added = stringResource(Res.string.download_added)
    val alreadyDownloaded = video.id in LocalAvailableOffline.current
    var confirmed by remember(video.id) { mutableStateOf(false) }
    if (alreadyDownloaded && !confirmed) {
        RedownloadDialog(video.title, onConfirm = { confirmed = true }, onDismiss = onDone)
        return
    }
    LaunchedEffect(video) {
        val gated = graph.mediaStore != null && graph.libraryFolderPicker != null
        if (preset != null && (!gated || hasUsableDownloadLocation(graph) || pickDownloadFolder(graph))) {
            downloads.enqueue(preset.request(video))
            snackbar(added)
        }
        onDone()
    }
}
