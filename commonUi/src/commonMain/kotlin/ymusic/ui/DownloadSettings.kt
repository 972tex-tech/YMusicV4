package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.settings.put

/**
 * The Settings screen's "Downloads" group: where finished downloads land (a Library folder) and which
 * caption languages to fetch alongside them. Rendered by [SettingsScreen] only when the shell has a
 * download service ([AppGraph.downloads] non-null); the location row additionally needs a media store,
 * since the download folder is one of the Library buckets.
 */
@Composable
internal fun DownloadSettingsSection(graph: AppGraph) {
    if (graph.downloads == null) return
    SettingsSectionHeader("Downloads")
    if (graph.mediaStore != null) DownloadLocationRow(graph)
    PreferredCaptionsRow(graph)
}

@Composable
private fun DownloadLocationRow(graph: AppGraph) {
    val mediaStore = graph.mediaStore ?: return
    val scope = rememberCoroutineScope()
    val location by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()

    var buckets by remember { mutableStateOf<List<MediaStoreBucket>>(emptyList()) }
    LaunchedEffect(mediaStore) {
        buckets = mediaStore.childBuckets(null)
        mediaStore.bucketsChanges().collect { buckets = mediaStore.childBuckets(null) }
    }

    val currentName = buckets.firstOrNull { it.uri.toString() == location }?.name
        ?: location.ifBlank { null }
    var open by remember { mutableStateOf(false) }

    SettingRow(title = "Download location", value = currentName ?: "Not set") { open = true }

    // "Add new folder…" picks + scans a folder (a new Library bucket), then adopts it as the location.
    val scan = graph.scanLibraryFolder
    val onAddFolder: (() -> Unit)? = if (scan == null) {
        null
    } else {
        {
            open = false
            scope.launch {
                val before = mediaStore.allBuckets().map { it.uri.toString() }.toSet()
                if (scan { } != null) {
                    val added = mediaStore.allBuckets().firstOrNull { it.uri.toString() !in before }
                    if (added != null) graph.settings.put(AppSettings.downloadLocation, added.uri.toString())
                }
            }
        }
    }

    if (open) {
        DownloadLocationDialog(
            buckets = buckets,
            current = location,
            onPick = { uri -> scope.launch { graph.settings.put(AppSettings.downloadLocation, uri) }; open = false },
            onAddFolder = onAddFolder,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun DownloadLocationDialog(
    buckets: List<MediaStoreBucket>,
    current: String,
    onPick: (String) -> Unit,
    onAddFolder: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Download location", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space2)) {
                if (buckets.isEmpty()) {
                    Text(
                        "No Library folders yet — add one to download into it.",
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    )
                }
                buckets.forEach { bucket ->
                    val selected = bucket.uri.toString() == current
                    ChoiceRow(label = bucket.name, selected = selected) { onPick(bucket.uri.toString()) }
                }
                if (onAddFolder != null) {
                    TextButton(onClick = onAddFolder, modifier = Modifier.fillMaxWidth()) {
                        Text("Add new folder…", modifier = Modifier.fillMaxWidth(), color = DesignColors.Accent)
                    }
                }
            }
        },
    )
}

@Composable
private fun PreferredCaptionsRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val selected by remember(graph) { graph.settings.flow(AppSettings.preferredCaptionLanguages) }.collectAsState()

    val summary = if (selected.isEmpty()) {
        "Default track only"
    } else {
        selected.joinToString(", ") { tag -> UiLanguages.firstOrNull { it.tag == tag }?.nativeName ?: tag }
    }
    var open by remember { mutableStateOf(false) }

    SettingRow(title = "Caption languages", value = summary) { open = true }

    if (open) {
        CaptionLanguagesDialog(
            selected = selected,
            onConfirm = { picked -> scope.launch { graph.settings.put(AppSettings.preferredCaptionLanguages, picked) }; open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun CaptionLanguagesDialog(
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local working copy so toggles don't churn the store until the user confirms.
    var working by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(working) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Caption languages", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Download these caption tracks when a video has them.",
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(bottom = DesignSpacing.Space8),
                )
                UiLanguages.forEach { lang ->
                    val checked = lang.tag in working
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                working = if (checked) working - lang.tag else working + lang.tag
                            }
                            .padding(vertical = DesignSpacing.Space4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(lang.nativeName, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
                    }
                }
            }
        },
    )
}

/** A plain settings row: bold title on the left, current value on the right, whole row tappable. */
@Composable
internal fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DesignSpacing.SettingsRowHorizontal, vertical = DesignSpacing.SettingsRowVertical),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.weight(1f),
        )
        Text(value, style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary),
        modifier = Modifier.padding(
            start = DesignSpacing.SettingsRowHorizontal,
            end = DesignSpacing.SettingsRowHorizontal,
            top = DesignSpacing.Space16,
            bottom = DesignSpacing.Space4,
        ),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) DesignColors.Accent else DesignColors.Ink,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DesignSpacing.Space8),
    )
}
