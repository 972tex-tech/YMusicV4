package ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ymusic.download.DownloadFormat
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadTask
import ymusic.download.DownloadTasksAggregator

/**
 * The Download Manager. Renders the [DownloadService] queue as Media Cards grouped into status sections
 * (Downloading / Queued / Paused / Needs attention / Completed), in an adaptive grid — one column on a
 * phone, more as width allows. A Pause-all / Resume-all FAB sits bottom-right; each card has a per-state
 * overflow menu (pause/resume/cancel/retry/remove) and an inline Retry on failures.
 */
@Composable
fun DownloadsScreen(graph: AppGraph, coordinator: PlaybackCoordinator) {
    val service = graph.downloads
    if (service == null) {
        CenteredBox { Text("Downloads aren't available on this device.") }
        return
    }
    // Own the aggregator for as long as this screen is composed: it seeds once and folds change events,
    // and is dropped (its DB subscription cancelled) when the user leaves.
    val aggregator = remember(service) { DownloadTasksAggregator(service::snapshot, service.changes) }
    val tasks by aggregator.tasks.collectAsState(emptyList())

    val visible = tasks.filter { it.state != DownloadState.CANCELED }
    if (visible.isEmpty()) {
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

    // Sections in fixed order; within each, newest first.
    val sections = DownloadSection.entries.mapNotNull { section ->
        visible.filter { it.state.section() == section }
            .sortedByDescending { it.createdAt }
            .takeIf { it.isNotEmpty() }
            ?.let { section to it }
    }
    val running = visible.any { it.state == DownloadState.QUEUED || it.state.isActive }
    val paused = visible.any { it.state == DownloadState.PAUSED }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(DownloadCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesignSpacing.MarginCompact,
                end = DesignSpacing.MarginCompact,
                top = DesignSpacing.Space8,
                bottom = FabClearance, // so the last card isn't hidden behind the FAB
            ),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            sections.forEach { (section, items) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header:${section.name}") {
                    SectionHeader(section.title, items.size)
                }
                items(items, key = { it.id }) { task -> DownloadCard(task, service) }
            }
        }

        if (running || paused) {
            ExtendedFloatingActionButton(
                onClick = { if (running) service.pauseAll() else service.resumeAll() },
                text = { Text(if (running) "Pause all" else "Resume all") },
                icon = { Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(DesignSpacing.Space16),
            )
        }
    }
}

/** The status sections, in display order. CANCELED has no section (it's filtered out of the screen). */
private enum class DownloadSection(val title: String) {
    ACTIVE("Downloading"),
    QUEUED("Queued"),
    PAUSED("Paused"),
    FAILED("Needs attention"),
    COMPLETED("Completed"),
}

private fun DownloadState.section(): DownloadSection? = when (this) {
    DownloadState.PREPARING, DownloadState.DOWNLOADING, DownloadState.PROCESSING -> DownloadSection.ACTIVE
    DownloadState.QUEUED -> DownloadSection.QUEUED
    DownloadState.PAUSED -> DownloadSection.PAUSED
    DownloadState.FAILED -> DownloadSection.FAILED
    DownloadState.COMPLETED -> DownloadSection.COMPLETED
    DownloadState.CANCELED -> null
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = DesignSpacing.Space8, bottom = DesignSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One download as a Media Card: art + title/artist/status pill + overflow, with the progress bar below
 *  and an inline Retry on failures. */
@Composable
private fun DownloadCard(task: DownloadTask, service: DownloadService) {
    Surface(
        shape = RoundedCornerShape(DesignSpacing.Space16),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                LibraryThumb(icon = Icons.Outlined.FileDownload, badge = task.format.badge(), artUrl = task.thumbnailUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space2)) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    task.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                        StatusPill(task)
                        task.detailText()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                MoreMenu(actionsFor(task, service))
            }
            DownloadProgress(task)
            if (task.state == DownloadState.FAILED) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                    Text(
                        task.errorMessage ?: "Download failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { service.retry(task.id) }) { Text("Retry") }
                }
            }
        }
    }
}

/** Small state chip (Downloading / Queued / Paused / Processing / Failed / Done), colored by state. */
@Composable
private fun StatusPill(task: DownloadTask) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (task.state) {
        DownloadState.QUEUED -> Triple("Queued", scheme.surfaceVariant, scheme.onSurfaceVariant)
        DownloadState.PREPARING -> Triple("Preparing", scheme.primaryContainer, scheme.onPrimaryContainer)
        DownloadState.DOWNLOADING -> Triple("Downloading", scheme.primaryContainer, scheme.onPrimaryContainer)
        DownloadState.PROCESSING -> Triple("Processing", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        DownloadState.PAUSED -> Triple("Paused", scheme.surfaceVariant, scheme.onSurfaceVariant)
        DownloadState.FAILED -> Triple("Failed", scheme.errorContainer, scheme.onErrorContainer)
        DownloadState.COMPLETED -> Triple("Done", scheme.secondaryContainer, scheme.onSecondaryContainer)
        DownloadState.CANCELED -> Triple("Canceled", scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    StatusChip(label, container, content)
}

@Composable
private fun StatusChip(label: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(percent = 50), color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
        )
    }
}

/** A determinate bar while a fraction is known (download bytes, or ffmpeg output-time during processing),
 *  indeterminate while preparing or before the first tick, nothing when terminal. */
@Composable
private fun DownloadProgress(task: DownloadTask) {
    when {
        (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.PROCESSING) && task.progress != null ->
            LinearProgressIndicator(
                progress = { task.progress!! },
                modifier = Modifier.fillMaxWidth(),
                gapSize = 0.dp, // solid bar — drop the M3 track gap …
                drawStopIndicator = {}, // … and the trailing stop-indicator dot
            )
        task.state == DownloadState.PREPARING || task.state == DownloadState.PROCESSING ||
            task.state == DownloadState.DOWNLOADING ->
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

/** The size/percent detail shown beside the status pill, or null when there's nothing to add. */
private fun DownloadTask.detailText(): String? = when (state) {
    DownloadState.DOWNLOADING -> {
        val pct = progress?.let { " · ${(it * 100).toInt()}%" } ?: ""
        "${humanSize(downloadedBytes)} / ${humanSize(totalBytes)}$pct"
    }
    // During processing the byte counters carry ffmpeg's output-time fraction, not bytes — show only %.
    DownloadState.PROCESSING -> progress?.let { "${(it * 100).toInt()}%" }
    DownloadState.COMPLETED -> humanSize(totalBytes)
    else -> null
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "${(mb * 10).toLong() / 10.0} MB" else "${bytes / 1024} KB"
}

/** Adaptive grid: one column on a phone, more as width allows. */
private val DownloadCardMinWidth = 340.dp

/** Bottom content inset so the FAB doesn't cover the last card (FAB height + margins). */
private val FabClearance = 88.dp
