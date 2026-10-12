package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.update_ask_later
import io.ymusic.ui.resources.update_auto_checkbox
import io.ymusic.ui.resources.update_available_title
import io.ymusic.ui.resources.update_download
import io.ymusic.ui.resources.update_downloading
import io.ymusic.ui.resources.update_failed
import io.ymusic.ui.resources.update_full_changelog
import io.ymusic.ui.resources.update_install_now
import io.ymusic.ui.resources.update_manual_hint
import io.ymusic.ui.resources.update_ready
import io.ymusic.ui.resources.update_version
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The update prompt: the version, its brief notes (and a link to the full changelog), "Ask later",
 * and one action — "Install now" when this release can replace the installed copy in place, or
 * "Download" (the release's web page) when it cannot (`installInApp` false, or self-update blocked).
 *
 * Shown once per release — [AppUpdates.dismiss] silences a version the user asked about later, so a
 * declined update never nags again. "Install now" keeps the dialog up through the download and hands
 * straight to the installer; a background download stays silent until it is Ready.
 */
@Composable
fun UpdateDialog(graph: AppGraph) {
    val updates = graph.updates ?: return
    val status by updates.status.collectAsState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var dismissedVersion by remember { mutableStateOf(-1) }
    var installRequested by remember { mutableStateOf(false) }

    val versionCode = when (val s = status) {
        is AppUpdateStatus.Available -> s.versionCode
        is AppUpdateStatus.Ready -> s.versionCode
        else -> -1
    }
    LaunchedEffect(status, installRequested) {
        if (installRequested && status is AppUpdateStatus.Ready) {
            installRequested = false
            updates.installAndRestart()
        }
    }
    if (status is AppUpdateStatus.None || versionCode == dismissedVersion) return
    if (status is AppUpdateStatus.Downloading && !installRequested) return

    val blocked = updates.blockedReason
    val inPlace = blocked == null && when (val s = status) {
        is AppUpdateStatus.Available -> s.installInApp
        else -> true
    }
    val changelogUrl = when (val s = status) {
        is AppUpdateStatus.Available -> s.changelogUrl
        is AppUpdateStatus.Ready -> s.changelogUrl
        else -> null
    }
    val later = {
        dismissedVersion = versionCode
        installRequested = false
        updates.dismiss(versionCode)
    }

    ElevatedBarDialog(
        title = stringResource(Res.string.update_available_title),
        onDismiss = later,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.update_ask_later), onClick = later)
            when (status) {
                is AppUpdateStatus.Available ->
                    if (inPlace) {
                        TextButton(onClick = { installRequested = true; updates.download() }) {
                            Text(stringResource(Res.string.update_install_now))
                        }
                    } else {
                        TextButton(onClick = { updates.openDownloadPage() }) {
                            Text(stringResource(Res.string.update_download))
                        }
                    }
                is AppUpdateStatus.Ready ->
                    TextButton(onClick = { updates.installAndRestart() }) {
                        Text(stringResource(Res.string.update_install_now))
                    }
                is AppUpdateStatus.Failed ->
                    TextButton(onClick = { updates.openDownloadPage() }) {
                        Text(stringResource(Res.string.update_download))
                    }
                else -> Unit
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            val name = when (val s = status) {
                is AppUpdateStatus.Available -> s.versionName
                is AppUpdateStatus.Ready -> s.versionName
                is AppUpdateStatus.Downloading -> s.versionName
                else -> ""
            }
            if (name.isNotEmpty()) {
                Text(
                    stringResource(Res.string.update_version, name),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                )
            }
            when (val s = status) {
                is AppUpdateStatus.Available -> ChangeNotes(s.notes)
                is AppUpdateStatus.Ready -> {
                    Text(
                        stringResource(Res.string.update_ready),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.Accent),
                    )
                    ChangeNotes(s.notes)
                }
                is AppUpdateStatus.Downloading -> {
                    Text(
                        stringResource(
                            Res.string.update_downloading,
                            formatBytes(s.done),
                            formatBytes(s.total),
                        ),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                    )
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) (s.done.toFloat() / s.total) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is AppUpdateStatus.Failed -> Text(
                    stringResource(Res.string.update_failed, s.reason),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                )
                AppUpdateStatus.None -> Unit
            }
            if (changelogUrl != null) {
                Text(
                    stringResource(Res.string.update_full_changelog),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
                    modifier = Modifier.clickable { uriHandler.openUri(changelogUrl) }
                        .padding(vertical = DesignSpacing.Space4),
                )
            }

            if (blocked != null) {
                Text(
                    stringResource(Res.string.update_manual_hint, blocked),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                )
            } else if (inPlace) {
                // Opting in here means every later release downloads in the background and applies
                // on the next start, so this prompt stops appearing before the work is done.
                val auto by remember(graph) {
                    graph.settings.flow(AppSettings.autoDownloadUpdates)
                }.collectAsState()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { scope.launch { graph.settings.put(AppSettings.autoDownloadUpdates, !auto) } }
                        .padding(vertical = DesignSpacing.Space8),
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = auto, onCheckedChange = null)
                    Text(
                        stringResource(Res.string.update_auto_checkbox),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.Ink),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeNotes(notes: String) {
    if (notes.isBlank()) return
    Text(
        notes,
        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
