package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
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

    if (open) {
        ChooseDownloadLocationDialog(graph, onDismiss = { open = false }, onChosen = { open = false })
    }
}

/**
 * Pick where downloads go: choose an existing Library folder, or "Add new folder…" to pick one from
 * disk (a new Library bucket) and adopt it. Writes [AppSettings.downloadLocation] on a pick.
 * [onChosen] fires when a location was set (the download flow uses it to advance past the gate);
 * [onDismiss] fires on cancel. Both close the dialog. No-ops itself if the shell has no media store.
 */
@Composable
internal fun ChooseDownloadLocationDialog(graph: AppGraph, onDismiss: () -> Unit, onChosen: () -> Unit) {
    val mediaStore = graph.mediaStore ?: return
    val scope = rememberCoroutineScope()
    val location by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()

    var buckets by remember { mutableStateOf<List<MediaStoreBucket>>(emptyList()) }
    LaunchedEffect(mediaStore) {
        buckets = mediaStore.childBuckets(null)
        mediaStore.bucketsChanges().collect { buckets = mediaStore.childBuckets(null) }
    }

    val scan = graph.scanLibraryFolder
    val onAddFolder: (() -> Unit)? = if (scan == null) {
        null
    } else {
        {
            scope.launch {
                val before = mediaStore.allBuckets().map { it.uri.toString() }.toSet()
                val added = if (scan { } != null) {
                    mediaStore.allBuckets().firstOrNull { it.uri.toString() !in before }
                } else {
                    null
                }
                if (added != null) {
                    graph.settings.put(AppSettings.downloadLocation, added.uri.toString())
                    onChosen()
                } else {
                    onDismiss() // canceled the picker, or nothing new was added
                }
            }
        }
    }

    DownloadLocationDialog(
        buckets = buckets,
        current = location,
        onPick = { uri ->
            scope.launch { graph.settings.put(AppSettings.downloadLocation, uri) }
            onChosen()
        },
        onAddFolder = onAddFolder,
        onDismiss = onDismiss,
    )
}

@Composable
private fun DownloadLocationDialog(
    buckets: List<MediaStoreBucket>,
    current: String,
    onPick: (String) -> Unit,
    onAddFolder: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = "Download location",
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            if (buckets.isEmpty()) {
                Text(
                    "No Library folders yet — add one to download into it.",
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    modifier = Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
                )
            }
            buckets.forEach { bucket ->
                ChoiceRow(label = bucket.name, selected = bucket.uri.toString() == current) { onPick(bucket.uri.toString()) }
            }
            if (onAddFolder != null) {
                Text(
                    "Add new folder…",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddFolder)
                        .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
                )
            }
        }
    }
}

/**
 * When the user adds their very first Library folder and no download location is set yet, offer to make
 * that folder the default download location. Mounted app-wide (App.kt); watches the buckets table for a
 * 0 → 1+ transition so it fires on the add, not on every launch that already has folders.
 */
@Composable
internal fun DefaultDownloadLocationPrompt(graph: AppGraph) {
    val mediaStore = graph.mediaStore ?: return
    if (graph.downloads == null) return
    val scope = rememberCoroutineScope()
    val location by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()

    var candidate by remember { mutableStateOf<MediaStoreBucket?>(null) }
    LaunchedEffect(mediaStore) {
        var previous = mediaStore.childBuckets(null).size
        mediaStore.bucketsChanges().collect {
            val roots = mediaStore.childBuckets(null)
            val locationUnset = graph.settings.flow(AppSettings.downloadLocation).value.isBlank()
            if (previous == 0 && roots.isNotEmpty() && locationUnset) candidate = roots.first()
            previous = roots.size
        }
    }

    val bucket = candidate
    if (bucket != null && location.isBlank()) {
        ElevatedBarDialog(
            title = "Download location",
            onDismiss = { candidate = null },
            canScrollBackward = false,
            canScrollForward = false,
            buttons = {
                TextButton(onClick = { candidate = null }) { Text("Not now") }
                TextButton(onClick = {
                    scope.launch { graph.settings.put(AppSettings.downloadLocation, bucket.uri.toString()) }
                    candidate = null
                }) { Text("Set as default") }
            },
        ) {
            Text(
                "Save downloads to “${bucket.name}”? You can change this in Settings.",
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
                modifier = Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
            )
        }
    }
}

@Composable
private fun PreferredCaptionsRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val selected by remember(graph) { graph.settings.flow(AppSettings.preferredCaptionLanguages) }.collectAsState()

    val summary = if (selected.isEmpty()) {
        "Default track only"
    } else {
        selected.joinToString(", ") { captionLanguageName(it) }
    }
    var open by remember { mutableStateOf(false) }

    SettingRow(title = "Caption languages", value = summary) { open = true }

    if (open) {
        CaptionLanguagesDialog(
            selected = selected,
            onConfirm = { picked ->
                scope.launch { graph.settings.put(AppSettings.preferredCaptionLanguages, picked) }
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * Caption-language picker: chips for the current selection + a type-to-filter field whose suggestions
 * (matched on name or code) add a chip on tap. Changes stay in a local working set until Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionLanguagesDialog(
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val suggestions = remember(query, working) {
        val q = query.trim().lowercase()
        CaptionLanguages
            .filter { it.code !in working }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.startsWith(q) }
            .take(6)
    }

    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = "Caption languages",
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onConfirm(working) }) { Text("Save") }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            Text(
                "Download these caption tracks when a video has them.",
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
            if (working.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
                    working.forEach { code ->
                        InputChip(
                            selected = true,
                            onClick = { working = working - code },
                            label = { Text(captionLanguageName(code)) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove") },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Add a language") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (query.isNotEmpty()) ActionIcon(Icons.Default.Close, "clear") { query = "" }
                },
            )
            suggestions.forEach { lang ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { working = working + lang.code; query = "" }
                        .padding(vertical = DesignSpacing.Space8),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    Text(lang.name, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink), modifier = Modifier.weight(1f))
                    Text(lang.code, style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
                }
            }
        }
    }
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
            .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
    )
}
