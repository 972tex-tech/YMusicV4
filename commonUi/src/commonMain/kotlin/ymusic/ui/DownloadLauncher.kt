package ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import ymusic.download.DownloadFormat
import ymusic.download.DownloadRequest
import ymusic.dynamicengine.yt.YtFeedItem

/**
 * Opens the download-format picker for a video. Provided app-wide by [DownloadDialogHost] (null when the
 * shell has no download service), so any video menu — feed rows, Now Playing — can offer "Download"
 * without threading the service through every call site.
 */
fun interface DownloadLauncher {
    fun launch(video: YtFeedItem)
}

/** Null when downloads are unavailable (iOS, headless graphs) — the menus then omit the Download action. */
val LocalDownloadLauncher = staticCompositionLocalOf<DownloadLauncher?> { null }

/**
 * Hosts the format-choice dialog for the currently-[pending] video and enqueues the pick into
 * `graph.downloads`. Rendered once inside the app's CompositionLocal scope so it can reach
 * [LocalSnackbar]; a no-op when nothing is pending. MP3 and video (mux) options are hidden where ffmpeg
 * is absent (iOS) — only the raw-stream M4A remains.
 */
@Composable
internal fun DownloadDialogHost(graph: AppGraph, pending: YtFeedItem?, onDismiss: () -> Unit) {
    val downloads = graph.downloads ?: return
    val video = pending ?: return
    val snackbar = LocalSnackbar.current

    fun pick(format: DownloadFormat, height: Int?) {
        downloads.enqueue(
            DownloadRequest(
                videoId = video.id,
                title = video.title,
                artist = video.subtitleLine1,
                thumbnailUrl = video.thumbs.firstOrNull()?.url,
                format = format,
                requestedHeight = height,
            ),
        )
        snackbar("Added to downloads")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Download", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space2)) {
                // Audio-only M4A is the raw best-AAC stream — the one output that needs no ffmpeg.
                DownloadOption("Audio · M4A") { pick(DownloadFormat.M4A, null) }
                if (downloads.ffmpegAvailable) {
                    DownloadOption("Audio · MP3") { pick(DownloadFormat.MP3, null) }
                    DownloadOption("Video · 1080p (MP4)") { pick(DownloadFormat.VIDEO_MP4, 1080) }
                    DownloadOption("Video · 720p (MP4)") { pick(DownloadFormat.VIDEO_MP4, 720) }
                }
            }
        },
    )
}

@Composable
private fun DownloadOption(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
