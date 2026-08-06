package ymusic.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kmpcommon.uri.Uri
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ymusic.download.DownloadFormat
import ymusic.download.DownloadService
import ymusic.download.DownloadState
import ymusic.download.DownloadTask
import ymusic.download.DownloadTasksAggregator

/**
 * The Download Manager. Media Cards grouped into status sections (Downloading / Queued / Paused / Needs
 * attention / Completed) in an adaptive grid — one column on a phone, more as width allows — with a
 * Pause-all / Resume-all FAB bottom-right. Each card: art + title + a status pill, the progress bar and
 * byte counts aligned under the text, a per-state overflow menu, and an inline Retry on failures.
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

    // Sections in fixed order. Completed sorts by finish time (updatedAt, the date shown on the card);
    // the rest keep queue order (createdAt), newest first.
    val sections = DownloadSection.entries.mapNotNull { section ->
        val items = visible.filter { it.state.section() == section }
        val sorted = if (section == DownloadSection.COMPLETED) {
            items.sortedByDescending { it.updatedAt }
        } else {
            items.sortedByDescending { it.createdAt }
        }
        sorted.takeIf { it.isNotEmpty() }?.let { section to it }
    }
    val running = visible.any { it.state == DownloadState.QUEUED || it.state.isActive }
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
            sections.forEach { (section, items) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header:${section.name}") {
                    SectionHeader(section.title, items.size)
                }
                items(items, key = { it.id }) { task ->
                    DownloadCard(
                        task, service,
                        onPlay = { task.fileUri?.let { coordinator.playLocalUri(it, task.title, task.artist, task.thumbnailUrl) } },
                        onRevealLibrary = if (store != null) ({
                            scope.launch {
                                // Point the Library at the file's actual folder, then switch to it. Falls
                                // back to just opening the Library if the item isn't in the store yet.
                                folderReveal?.value = task.fileUri
                                    ?.let { store.findItemByUri(Uri.parse(it)) }?.bucketId
                                navigate(AppSection.Library)
                            }
                            Unit
                        }) else null,
                        onRevealFolder = graph.revealInFolder?.let { hook -> { task.fileUri?.let { uri -> scope.launch { hook(uri) } }; Unit } },
                    )
                }
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
    val danger = title == DownloadSection.FAILED.title
    Row(
        Modifier.fillMaxWidth().padding(top = DesignSpacing.Space16, bottom = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (danger) MaterialTheme.colorScheme.error else DesignColors.Ink,
        )
        StatusChip("$count", DesignColors.SurfaceDim, DesignColors.InkMuted, mono = true)
    }
}

/** One download as a Media Card: bordered surface, art + title + status pill + overflow up top; the
 *  progress bar and byte counts indented under the text; an inline Retry on failures. Matches the chosen
 *  "Media Cards" mockup. */
@Composable
private fun DownloadCard(
    task: DownloadTask,
    service: DownloadService,
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                LibraryThumb(icon = Icons.Outlined.FileDownload, badge = task.format.badge(), artUrl = task.thumbnailUrl, corner = CardCorner, badgeColor = task.format.badgeColor())
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
            task.byteLine()?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = ContentIndent))
            }
            if (task.state == DownloadState.COMPLETED) {
                Row(Modifier.padding(start = ContentIndent), horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                    Text(humanSize(task.totalBytes), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, fontFamily = FontFamily.Monospace)
                    Text(formatDownloadDate(task.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                }
            }
            if (failed) {
                Row(
                    Modifier.fillMaxWidth().padding(start = ContentIndent),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    Text(task.errorMessage ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { service.retry(task.id) },
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                        contentPadding = PaddingValues(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space4),
                    ) { Text("Retry", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** Which states show a bar: preparing (indeterminate), and downloading/processing/paused with a known
 *  fraction (determinate). Queued/completed/failed show none. */
private fun DownloadTask.showsBar(): Boolean = state == DownloadState.PREPARING ||
    ((state == DownloadState.DOWNLOADING || state == DownloadState.PROCESSING || state == DownloadState.PAUSED) && progress != null)

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
private fun StatusChip(label: String, container: Color, content: Color, mono: Boolean = false) {
    Surface(shape = RoundedCornerShape(percent = 50), color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontFamily = if (mono) FontFamily.Monospace else null,
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

private fun DownloadFormat.badge(): String = when (this) {
    DownloadFormat.MP3 -> "MP3"
    DownloadFormat.M4A -> "M4A"
    DownloadFormat.VIDEO_WEBM -> "WEBM"
    DownloadFormat.VIDEO_MP4 -> "MP4"
}

/** A distinct badge tint per format so the type reads at a glance (white text sits on all four). */
private fun DownloadFormat.badgeColor(): Color = when (this) {
    DownloadFormat.MP3 -> Color(0xFF2E7D32) // green
    DownloadFormat.M4A -> Color(0xFF1565C0) // blue
    DownloadFormat.VIDEO_MP4 -> Color(0xFF6A1B9A) // purple
    DownloadFormat.VIDEO_WEBM -> Color(0xFFB23C17) // rust
}

/** The byte-count line under the text: "used / total" while downloading/paused. Completed cards show
 *  size + finished date on their own row instead. */
private fun DownloadTask.byteLine(): String? = when (state) {
    DownloadState.DOWNLOADING, DownloadState.PAUSED -> "${humanSize(downloadedBytes)} / ${humanSize(totalBytes)}"
    else -> null
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
private val CardCorner = 6.dp // match the video thumbnail radius (RectThumb)
private val CardPadding = 14.dp
private val CardGap = 12.dp // thumb ↔ text
private val ContentIndent = 68.dp // ThumbSize (56) + CardGap (12): aligns the bar/bytes under the text
private val DownloadCardMinWidth = 340.dp // adaptive grid: 1 column on a phone, more as width allows
private val FabClearance = 88.dp // bottom inset so the FAB doesn't cover the last card
