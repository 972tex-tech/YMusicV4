package io.ymusic.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadService
import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadTask
import io.ymusic.download.DownloadTasksAggregator
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.downloads_empty_all
import io.ymusic.ui.resources.downloads_empty_completed
import io.ymusic.ui.resources.downloads_empty_error
import io.ymusic.ui.resources.downloads_empty_paused
import io.ymusic.ui.resources.downloads_empty_running
import io.ymusic.ui.resources.downloads_empty_waiting
import io.ymusic.ui.resources.downloads_filter_all
import io.ymusic.ui.resources.downloads_filter_completed
import io.ymusic.ui.resources.downloads_filter_error
import io.ymusic.ui.resources.downloads_filter_paused
import io.ymusic.ui.resources.downloads_filter_running
import io.ymusic.ui.resources.downloads_filter_waiting
import kmpcommon.uri.Uri
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Download Manager: a status filter chip row over one flat adaptive grid (one column on a phone,
 * more as width allows), with a Pause-all / Resume-all FAB bottom-right. Each card: art + title + a status
 * pill, the progress bar and byte counts aligned under the text, a per-state overflow menu, and an inline
 * Retry on failures.
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

    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    // Added order only, never state: a task changing state must keep its slot.
    val ordered = visible.sortedByDescending { it.createdAt }
    val shown = ordered.filter { filter.matches(it.state) }
    val running = visible.any { it.state.isActive }
    val paused = visible.any { it.state == DownloadState.PAUSED }

    // Report scroll into the shell's ScrollHost so the shared app bar lifts on scroll, like Library/Discover.
    val gridState = rememberLazyGridState()
    TrackScroll { gridState.canScrollBackward }

    val navigate = LocalNavigateToSection.current
    val folderReveal = LocalLibraryFolderReveal.current
    val store = graph.mediaStore
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(DownloadCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesignSpacing.MarginCompact,
                end = DesignSpacing.MarginCompact,
                top = DesignSpacing.Space8,
                bottom = FabClearance, // so the last card isn't hidden behind the FAB
            ),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    DownloadFilter.entries.forEach { entry ->
                        val label = stringResource(entry.label)
                        LibraryChip(
                            if (entry == DownloadFilter.ALL) label else "$label ${visible.count { entry.matches(it.state) }}",
                            selected = entry == filter,
                        ) { filter = entry }
                    }
                }
            }
            items(shown, key = { it.id }) { task ->
                DownloadCard(
                    task, service,
                    modifier = Modifier.animateItem(),
                    onPlay = {
                        task.fileUri?.let { uri ->
                            scope.launch { coordinator.playDownloaded(uri, task.videoId, task.title, task.artist) }
                        }
                    },
                    onRevealLibrary = if (store != null) ({
                        scope.launch {
                            // Resolve the folder from the file's own persisted uri, not the current
                            // download-location setting (which may have changed since): the item's
                            // registered bucket, else the file's parent folder as a Library bucket.
                            // Null falls back to just opening the Library.
                            val uri = task.fileUri
                            folderReveal?.value = uri?.let { store.findItemByUri(Uri.parse(it)) }?.bucketId
                                ?: uri?.parentFolderUri()?.let { store.findBucketByUri(Uri.parse(it)) }?.id
                            navigate(AppSection.Library)
                        }
                        Unit
                    }) else null,
                    onRevealFolder = graph.revealInFolder?.let { hook -> { task.fileUri?.let { uri -> scope.launch { hook(uri) } }; Unit } },
                )
            }
        }

        if (shown.isEmpty()) {
            CenteredBox(Modifier.fillMaxSize()) {
                Text(
                    stringResource(filter.empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** The filter chips, in display order. CANCELED matches none (it's filtered out of the screen). */
private enum class DownloadFilter(val label: StringResource, val empty: StringResource) {
    ALL(Res.string.downloads_filter_all, Res.string.downloads_empty_all),
    RUNNING(Res.string.downloads_filter_running, Res.string.downloads_empty_running),
    WAITING(Res.string.downloads_filter_waiting, Res.string.downloads_empty_waiting),
    PAUSED(Res.string.downloads_filter_paused, Res.string.downloads_empty_paused),
    COMPLETED(Res.string.downloads_filter_completed, Res.string.downloads_empty_completed),
    ERROR(Res.string.downloads_filter_error, Res.string.downloads_empty_error),
    ;

    fun matches(state: DownloadState): Boolean = when (this) {
        ALL -> state != DownloadState.CANCELED
        RUNNING -> state == DownloadState.PREPARING || state == DownloadState.DOWNLOADING || state == DownloadState.PROCESSING
        WAITING -> state == DownloadState.QUEUED
        PAUSED -> state == DownloadState.PAUSED
        COMPLETED -> state == DownloadState.COMPLETED
        ERROR -> state == DownloadState.FAILED
    }
}

/** One download as a Media Card: bordered surface, art + title + status pill + overflow up top; the
 *  progress bar and byte counts indented under the text; an inline Retry on failures. Matches the chosen
 *  "Media Cards" mockup. */
@Composable
private fun DownloadCard(
    task: DownloadTask,
    service: DownloadService,
    modifier: Modifier,
    onPlay: () -> Unit,
    onRevealLibrary: (() -> Unit)?,
    onRevealFolder: (() -> Unit)?,
) {
    val c = downloadColors()
    val failed = task.state == DownloadState.FAILED
    Surface(
        shape = RoundedCornerShape(CardCorner),
        color = if (failed) c.dangerSoft else c.surface,
        border = BorderStroke(1.dp, if (failed) c.danger.copy(alpha = 0.4f) else c.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                LibraryThumb(icon = Icons.Outlined.FileDownload, badge = null, artUrl = task.thumbnailUrl, corner = CardCorner)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                        StatusPill(task, c)
                        if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.PROCESSING) {
                            task.progress?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
                MoreMenu(actionsFor(task, service, onPlay, onRevealLibrary, onRevealFolder))
            }
            if (task.showsBar()) {
                Box(Modifier.fillMaxWidth().padding(start = ContentIndent)) { DownloadBar(task, c) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                Box(Modifier.width(ThumbSize), contentAlignment = Alignment.Center) { FormatBadge(task.format) }
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    when (task.state) {
                        DownloadState.DOWNLOADING, DownloadState.PAUSED -> Text(
                            "${humanSize(task.downloadedBytes)} / ${humanSize(task.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.inkMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                        DownloadState.COMPLETED -> {
                            Text(humanSize(task.totalBytes), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace)
                            Text(formatDownloadDate(task.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                        }
                        DownloadState.FAILED -> {
                            Text(task.errorMessage ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { service.retry(task.id) },
                                shape = RoundedCornerShape(percent = 50),
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                                contentPadding = PaddingValues(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space4),
                            ) { Text("Retry", style = MaterialTheme.typography.labelMedium) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Which states show a bar: preparing (indeterminate), and downloading/processing/paused with a known
 *  fraction (determinate). Queued/completed/failed show none. */
private fun DownloadTask.showsBar(): Boolean = state == DownloadState.PREPARING ||
    ((state == DownloadState.DOWNLOADING || state == DownloadState.PROCESSING || state == DownloadState.PAUSED) && progress != null)

/** The containing folder of a `file://` uri, as a bucket uri; null for opaque uris (Android SAF
 *  `content://` documents, whose parent isn't derivable by string ops — those resolve via findItemByUri). */
private fun String.parentFolderUri(): String? =
    if (startsWith("file://")) substringBeforeLast('/').takeIf { it.length > "file://".length } else null

@Composable
private fun DownloadBar(task: DownloadTask, c: DownloadColors) {
    if (task.state == DownloadState.PREPARING || task.progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.track)
    } else {
        LinearProgressIndicator(
            progress = { task.progress!! },
            modifier = Modifier.fillMaxWidth(),
            color = if (task.state == DownloadState.PAUSED) c.inkMuted else c.accent,
            trackColor = c.track,
            gapSize = 0.dp, // solid bar, no M3 track gap or stop-indicator dot
            drawStopIndicator = {},
        )
    }
}

/** Colored state chip; the queued one is neutral, the rest semantic (teal active / amber paused / green
 *  done / red failed) — matching the mockup's pill roles. */
@Composable
private fun StatusPill(task: DownloadTask, c: DownloadColors) {
    val (label, bg, fg) = when (task.state) {
        DownloadState.QUEUED -> Triple("Queued", c.neutral, c.inkMuted)
        DownloadState.PREPARING -> Triple("Preparing", c.accentSoft, c.accent)
        DownloadState.DOWNLOADING -> Triple("Downloading", c.accentSoft, c.accent)
        DownloadState.PROCESSING -> Triple("Processing", c.accentSoft, c.accent)
        DownloadState.PAUSED -> Triple("Paused", c.warningSoft, c.warning)
        DownloadState.FAILED -> Triple("Failed", c.dangerSoft, c.danger)
        DownloadState.COMPLETED -> Triple("Done", c.successSoft, c.success)
        DownloadState.CANCELED -> Triple("Canceled", c.neutral, c.inkMuted)
    }
    StatusChip(label, bg, fg)
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

/** Build the per-state overflow menu. Labels are hardcoded (English) to steer clear of the
 *  compose-resources accessor-chunk boundary hazard when adding many new strings at once. */
private fun actionsFor(
    task: DownloadTask,
    service: DownloadService,
    onPlay: () -> Unit,
    onRevealLibrary: (() -> Unit)?,
    onRevealFolder: (() -> Unit)?,
): ItemActions {
    val entries = buildList<Pair<String, () -> Unit>> {
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
            DownloadState.COMPLETED -> {
                add("Play" to onPlay)
                onRevealLibrary?.let { add("Reveal in Library" to it) }
                onRevealFolder?.let { add("Reveal in Finder" to it) }
                add("Remove" to { service.remove(task.id) })
            }
            DownloadState.CANCELED -> {
                add("Remove" to { service.remove(task.id) })
            }
        }
    }
    return ItemActions(entries)
}

@Composable
private fun FormatBadge(format: DownloadFormat) {
    Text(
        format.badge(),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(format.badgeColor(), RoundedCornerShape(BadgeCorner))
            .padding(horizontal = DesignSpacing.Space4, vertical = DesignSpacing.Space2),
    )
}

private fun DownloadFormat.badge(): String = when (this) {
    DownloadFormat.MP3 -> "MP3"
    DownloadFormat.M4A -> "M4A"
    DownloadFormat.AUDIO_WEBM -> "OPUS"
    DownloadFormat.VIDEO_WEBM -> "WEBM"
    DownloadFormat.VIDEO_MP4 -> "MP4"
}

/** A distinct badge tint per format so the type reads at a glance (white text sits on all of them). */
private fun DownloadFormat.badgeColor(): Color = when (this) {
    DownloadFormat.MP3 -> Color(0xFF2E7D32) // green
    DownloadFormat.M4A -> Color(0xFF1565C0) // blue
    DownloadFormat.AUDIO_WEBM -> Color(0xFF00695C) // teal
    DownloadFormat.VIDEO_MP4 -> Color(0xFF6A1B9A) // purple
    DownloadFormat.VIDEO_WEBM -> Color(0xFFB23C17) // rust
}

/** `updatedAt` (a completed task's finish time) as e.g. "Jul 31, 2026", in the device's time zone. */
private fun formatDownloadDate(epochMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${MONTH_ABBR[dt.monthNumber - 1]} ${dt.dayOfMonth}, ${dt.year}"
}

private val MONTH_ABBR = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "${(mb * 10).toLong() / 10.0} MB" else "${bytes / 1024} KB"
}

/** The download screen's semantic palette — the app's surfaces/ink/accent plus amber/green for the
 *  paused/done states (M3 has no warning/success role), matching the "Media Cards" mockup. */
private class DownloadColors(
    val surface: Color,
    val border: Color,
    val track: Color,
    val neutral: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
)

@Composable
private fun downloadColors(): DownloadColors {
    val d = DesignColors
    val amber = if (d.isDark) Color(0xFFE3A63E) else Color(0xFFB5720F)
    val green = if (d.isDark) Color(0xFF57C983) else Color(0xFF288A50)
    return DownloadColors(
        surface = d.SurfaceDim, // subtle lift from the screen surface — not the high-contrast elevated tone
        border = d.Divider,
        track = d.Placeholder,
        neutral = d.Placeholder,
        ink = d.Ink,
        inkMuted = d.InkMuted,
        accent = d.Accent,
        accentSoft = d.Accent.copy(alpha = 0.16f),
        onAccent = Color.White,
        warning = amber,
        warningSoft = amber.copy(alpha = if (d.isDark) 0.16f else 0.14f),
        success = green,
        successSoft = green.copy(alpha = if (d.isDark) 0.16f else 0.14f),
        danger = MaterialTheme.colorScheme.error,
        dangerSoft = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    )
}

// --- layout tokens (match the mockup's media-card metrics) ---------------------------------------
private val BadgeCorner = 3.dp
private val CardCorner = 6.dp // match the video thumbnail radius (RectThumb)
private val CardPadding = 14.dp
private val CardGap = 12.dp // thumb ↔ text
private val ThumbSize = 56.dp // LibraryThumb's size
private val ContentIndent = ThumbSize + CardGap // aligns the bar under the text
private val DownloadCardMinWidth = 340.dp // adaptive grid: 1 column on a phone, more as width allows
private val FabClearance = 88.dp // bottom inset so the FAB doesn't cover the last card
