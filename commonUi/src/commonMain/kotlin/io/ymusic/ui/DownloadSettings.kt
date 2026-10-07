package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.ymusic.mediastore.model.MediaStoreBucket
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import kmpcommon.uri.Uri
import kotlinx.coroutines.launch

/**
 * The Settings screen's "Downloads" group: where finished downloads land (a Library folder) and the Quick
 * download preset. Rendered by [SettingsScreen] only when the shell has a
 * download service ([AppGraph.downloads] non-null); the location row additionally needs a media store,
 * since the download folder is one of the Library buckets.
 */
@Composable
internal fun DownloadSettingsRows(graph: AppGraph) {
    if (graph.mediaStore != null) DownloadLocationRow(graph)
    QuickDownloadRow(graph)
}

@Composable
private fun DownloadLocationRow(graph: AppGraph) {
    if (graph.mediaStore == null) return
    val scope = rememberCoroutineScope()
    val name = rememberDownloadLocationName(graph)

    // No dialog: tapping goes straight to the OS folder picker (adds the folder to the Library and
    // sets it as the location). Existing folders are re-designated from the Library tab's menu instead.
    SettingRow(title = "Download location", value = name ?: "Not set") {
        scope.launch { pickDownloadFolder(graph) }
    }
}

/** The download location's Library bucket name, or null when unset — or when its folder no longer exists
 *  or is excluded (the next download re-validates and re-picks). One indexed lookup per change. */
@Composable
internal fun rememberDownloadLocationName(graph: AppGraph): String? {
    val mediaStore = graph.mediaStore ?: return null
    val location by remember(graph) { graph.settings.flow(AppSettings.downloadLocation) }.collectAsState()
    var name by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(location, mediaStore) {
        name = if (location.isBlank()) {
            null
        } else {
            mediaStore.findBucketByUri(Uri.parse(location))
                ?.takeIf { !it.excluded }
                ?.let { it.aliasName?.takeIf(String::isNotBlank) ?: it.name }
        }
    }
    return name
}

/**
 * Open the folder picker, register the chosen folder in the Library, and set it as
 * [AppSettings.downloadLocation]. Returns true iff a folder was chosen (false = no picker, or canceled).
 * The picker hands back the folder's uri, so this works whether the folder is new, already present,
 * empty, or nested — no guessing which bucket appeared.
 */
internal suspend fun pickDownloadFolder(graph: AppGraph): Boolean {
    val pick = graph.libraryFolderPicker ?: return false
    val uri = pick() ?: return false // canceled
    graph.settings.put(AppSettings.downloadLocation, uri)
    return true
}

/**
 * Whether the saved download location is usable right now: its folder must still be in the Library and
 * not excluded (a removed folder soft-deletes, so [BetterMediaStore.findBucketByUri] returns null). The
 * download flow checks this at start time and re-picks when it's false — no need to watch the store.
 */
internal suspend fun hasUsableDownloadLocation(graph: AppGraph): Boolean {
    val store = graph.mediaStore ?: return false
    val loc = graph.settings.flow(AppSettings.downloadLocation).value
    if (loc.isBlank()) return false
    val bucket = store.findBucketByUri(Uri.parse(loc)) ?: return false
    return !bucket.excluded
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
                DialogSecondaryButton("Not now") { candidate = null }
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
