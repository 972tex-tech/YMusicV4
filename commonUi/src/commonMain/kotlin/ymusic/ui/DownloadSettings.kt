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
import androidx.compose.material3.Checkbox
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
import kmpcommon.uri.Uri
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

    // Resolve the location uri to its (possibly non-root) bucket name; a single indexed lookup, not
    // an allBuckets() scan.
    var name by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(location, mediaStore) {
        name = if (location.isBlank()) {
            null
        } else {
            mediaStore.findBucketByUri(Uri.parse(location))?.let { it.aliasName?.takeIf(String::isNotBlank) ?: it.name }
                ?: location
        }
    }

    // No dialog: tapping goes straight to the OS folder picker (adds the folder to the Library and
    // sets it as the location). Existing folders are re-designated from the Library tab's menu instead.
    SettingRow(title = "Download location", value = name ?: "Not set") {
        scope.launch { pickDownloadFolder(graph) }
    }
}

/**
 * Show the OS folder picker; on pick, the folder is scanned into the Library (a new bucket) and set as
 * [AppSettings.downloadLocation]. Returns true iff a new folder was chosen. No-op (false) without a
 * media store or scan action, or when the pick was canceled / added nothing new.
 */
internal suspend fun pickDownloadFolder(graph: AppGraph): Boolean {
    val store = graph.mediaStore ?: return false
    val scan = graph.scanLibraryFolder ?: return false
    val before = store.childBuckets(null).map { it.uri.toString() }.toSet()
    if (scan { } == null) return false // canceled
    val added = store.childBuckets(null).firstOrNull { it.uri.toString() !in before } ?: return false
    graph.settings.put(AppSettings.downloadLocation, added.uri.toString())
    return true
}

/**
 * Clears the download location when its folder leaves the Library — removed (soft-deleted, so
 * [BetterMediaStore.findBucketByUri] returns null) or excluded (or cascade-deleted by excluding an
 * ancestor). The next download then re-prompts for a folder. Mounted app-wide (App.kt).
 */
@Composable
internal fun DownloadLocationGuard(graph: AppGraph) {
    val store = graph.mediaStore ?: return
    if (graph.downloads == null) return
    LaunchedEffect(store) {
        store.bucketsChanges().collect {
            val loc = graph.settings.flow(AppSettings.downloadLocation).value
            if (loc.isBlank()) return@collect
            val bucket = store.findBucketByUri(Uri.parse(loc))
            if (bucket == null || bucket.excluded) graph.settings.put(AppSettings.downloadLocation, "")
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
    val default by remember(graph) { graph.settings.flow(AppSettings.downloadDefaultCaption) }.collectAsState()

    val parts = buildList {
        if (default) add("Default")
        addAll(selected.map { captionLanguageName(it) })
    }
    val summary = if (parts.isEmpty()) "None" else parts.joinToString(", ")
    var open by remember { mutableStateOf(false) }

    SettingRow(title = "Caption languages", value = summary) { open = true }

    if (open) {
        CaptionLanguagesDialog(
            selected = selected,
            defaultEnabled = default,
            onConfirm = { picked, def ->
                scope.launch {
                    graph.settings.put(AppSettings.preferredCaptionLanguages, picked)
                    graph.settings.put(AppSettings.downloadDefaultCaption, def)
                }
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * Caption-language picker: a "default track" toggle, chips for the chosen languages, and a type-to-
 * filter field over the FULL scrollable language list (tap a row to add it). Changes stay in a local
 * working set until Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionLanguagesDialog(
    selected: Set<String>,
    defaultEnabled: Boolean,
    onConfirm: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(selected) }
    var default by remember { mutableStateOf(defaultEnabled) }
    var query by remember { mutableStateOf("") }
    // The whole catalogue, filtered by the query and minus what's already chosen — the dialog scrolls,
    // so the list is not capped (a short "suggestions" list read as "these are the only ones").
    val suggestions = remember(query, working) {
        val q = query.trim().lowercase()
        CaptionLanguages
            .filter { it.code !in working }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.startsWith(q) }
    }

    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = "Caption languages",
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onConfirm(working, default) }) { Text("Save") }
        },
    ) {
        // No horizontal padding on the scroll container: the toggle/header/chips/field inset themselves,
        // but the language rows stay full-bleed so their hover/ripple spans the whole dialog width.
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = DesignSpacing.Space8)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { default = !default }
                    .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = default, onCheckedChange = null)
                Text(
                    "Always download the default caption track",
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Also download these languages when a video offers them:",
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space8),
            )
            if (working.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                    modifier = Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal),
                ) {
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space8),
                trailingIcon = {
                    if (query.isNotEmpty()) ActionIcon(Icons.Default.Close, "clear") { query = "" }
                },
            )
            suggestions.forEach { lang ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { working = working + lang.code; query = "" }
                        .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.DialogRowVertical),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                    verticalAlignment = Alignment.CenterVertically,
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
