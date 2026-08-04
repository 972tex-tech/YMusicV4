package ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ymusic.download.DownloadFormat
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadTask

/**
 * The Download Manager. Renders the [DownloadService] queue as adaptive cards: a single-column list on
 * narrow windows, a fixed-column grid at/above [DownloadsGridMinWidth] (fewest columns keeping cards
 * ≤ [DownloadCardMaxWidth]). Each entry shows a thumbnail, title/subtitle, a progress bar for active
 * tasks, and a per-state overflow menu (pause/resume/cancel/retry/remove).
 */
@Composable
fun DownloadsScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val service = graph.downloads
    if (service == null) {
        CenteredBox { Text("Downloads aren't available on this device.") }
        return
    }
    val tasks by service.tasks.collectAsState()

    if (tasks.isEmpty()) {
        CenteredBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Downloaded songs and videos play offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // Newest first so an in-progress download sits at the top.
    val ordered = tasks.sortedByDescending { it.createdAt }

    BoxWithConstraintsCompat { maxWidth ->
        if (maxWidth >= DownloadsGridMinWidth) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(downloadColumns(maxWidth)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(DesignSpacing.MarginCompact),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Gutter),
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.GridRowGap),
            ) {
                items(ordered, key = { it.id }) { task -> DownloadCard(task, service) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = DesignSpacing.MarginCompact,
                    vertical = DesignSpacing.Space8,
                ),
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
            ) {
                items(ordered, key = { it.id }) { task -> DownloadRow(task, service) }
            }
        }
    }
}

/** List row: thumbnail + title/subtitle/progress on the left, overflow menu on the right. */
@Composable
private fun DownloadRow(task: DownloadTask, service: DownloadService) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        LibraryThumb(icon = Icons.Outlined.FileDownload, badge = task.format.badge(), artUrl = task.thumbnailUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space2)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                task.statusLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DownloadProgress(task)
        }
        MoreMenu(actionsFor(task, service))
    }
}

/** Grid card: a taller vertical form of the row (thumbnail on top, text + progress below). */
@Composable
private fun DownloadCard(task: DownloadTask, service: DownloadService) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(DesignSpacing.Space4),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            LibraryThumb(icon = Icons.Outlined.FileDownload, badge = task.format.badge(), artUrl = task.thumbnailUrl)
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MoreMenu(actionsFor(task, service))
        }
        Text(
            task.statusLine(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DownloadProgress(task)
    }
}

/** A determinate bar while bytes are known, indeterminate during prepare/process, nothing when terminal. */
@Composable
private fun DownloadProgress(task: DownloadTask) {
    when {
        task.state == DownloadState.DOWNLOADING && task.progress != null ->
            LinearProgressIndicator(progress = { task.progress!! }, modifier = Modifier.fillMaxWidth())
        task.state == DownloadState.PREPARING || task.state == DownloadState.PROCESSING ||
            (task.state == DownloadState.DOWNLOADING) ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else -> Unit
    }
}

/** Build the per-state overflow menu. Labels are hardcoded (English) to steer clear of the
 *  compose-resources accessor-chunk boundary hazard when adding many new strings at once. */
private fun actionsFor(task: DownloadTask, service: DownloadService): ItemActions {
    val entries = buildList {
        when (task.state) {
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING -> {
                add("Pause" to { service.pause(task.id) })
                add("Cancel" to { service.cancel(task.id) })
            }
            DownloadState.PAUSED -> {
                add("Resume" to { service.resume(task.id) })
                add("Cancel" to { service.cancel(task.id) })
            }
            DownloadState.FAILED -> {
                add("Retry" to { service.retry(task.id) })
                add("Remove" to { service.remove(task.id) })
            }
            DownloadState.COMPLETED, DownloadState.CANCELED -> {
                add("Remove" to { service.remove(task.id) })
            }
        }
    }
    return ItemActions(entries)
}

private fun DownloadFormat.badge(): String = when (this) {
    DownloadFormat.MP3 -> "MP3"
    DownloadFormat.M4A -> "M4A"
    DownloadFormat.VIDEO_WEBM -> "WEBM"
    DownloadFormat.VIDEO_MP4 -> "MP4"
}

/** One-line status: state name plus size/percent for an active or finished task. */
private fun DownloadTask.statusLine(): String {
    val label = when (state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.PREPARING -> "Preparing…"
        DownloadState.DOWNLOADING -> "Downloading"
        DownloadState.PROCESSING -> "Processing…"
        DownloadState.COMPLETED -> "Downloaded"
        DownloadState.PAUSED -> "Paused"
        DownloadState.FAILED -> "Failed"
        DownloadState.CANCELED -> "Canceled"
    }
    val detail = when (state) {
        DownloadState.DOWNLOADING -> {
            val pct = progress?.let { " · ${(it * 100).toInt()}%" } ?: ""
            "${humanSize(downloadedBytes)} / ${humanSize(totalBytes)}$pct"
        }
        DownloadState.COMPLETED -> humanSize(totalBytes)
        DownloadState.FAILED -> errorMessage ?: ""
        else -> ""
    }
    val artist = artist?.let { "$it · " } ?: ""
    return if (detail.isBlank()) "$artist$label" else "$artist$label · $detail"
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "${(mb * 10).toLong() / 10.0} MB" else "${bytes / 1024} KB"
}

/** The Downloads grid appears at/above this width; below it a single-column list. */
private val DownloadsGridMinWidth = 520.dp
private val DownloadCardMaxWidth = 300.dp

private fun downloadColumns(width: Dp): Int {
    val avail = (width - DesignSpacing.MarginCompact * 2).coerceAtLeast(0.dp)
    return kotlin.math.ceil(avail / DownloadCardMaxWidth).toInt().coerceAtLeast(2)
}

/** Local BoxWithConstraints wrapper handing the max width as a [Dp] (keeps the call sites tidy). */
@Composable
private fun BoxWithConstraintsCompat(content: @Composable (maxWidth: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) { content(this.maxWidth) }
}
