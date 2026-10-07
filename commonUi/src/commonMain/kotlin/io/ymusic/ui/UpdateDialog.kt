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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.update_auto_checkbox
import io.ymusic.ui.resources.update_available_title
import io.ymusic.ui.resources.update_download
import io.ymusic.ui.resources.update_downloading
import io.ymusic.ui.resources.update_failed
import io.ymusic.ui.resources.update_install_restart
import io.ymusic.ui.resources.update_later
import io.ymusic.ui.resources.update_manual_hint
import io.ymusic.ui.resources.update_open_download
import io.ymusic.ui.resources.update_ready
import io.ymusic.ui.resources.update_version
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The update prompt: what changed, and one action.
 *
 * Shown once per release — [AppUpdates.dismiss] silences a version the user said "later" to, so a
 * declined update never nags again. A copy that cannot update itself still gets the dialog, with
 * the reason and a link to download instead.
 */
@Composable
fun UpdateDialog(graph: AppGraph) {
    val updates = graph.updates ?: return
    val status by updates.status.collectAsState()
    val scope = rememberCoroutineScope()
    var dismissedVersion by remember { mutableStateOf(-1) }

    val versionCode = when (val s = status) {
        is AppUpdateStatus.Available -> s.versionCode
        is AppUpdateStatus.Ready -> s.versionCode
        else -> -1
    }
    if (status is AppUpdateStatus.None || versionCode == dismissedVersion) return
    // A background download is not worth interrupting for; it resurfaces once Ready.
    if (status is AppUpdateStatus.Downloading) return

    val blocked = updates.blockedReason
    val title = stringResource(Res.string.update_available_title)

    ElevatedBarDialog(
        title = title,
        onDismiss = { dismissedVersion = versionCode; updates.dismiss(versionCode) },
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.update_later)) {
                dismissedVersion = versionCode
                updates.dismiss(versionCode)
            }
            when (val s = status) {
                is AppUpdateStatus.Available ->
                    if (blocked == null) {
                        TextButton(onClick = { updates.download() }) {
                            Text(stringResource(Res.string.update_download))
                        }
                    } else {
                        TextButton(onClick = { updates.openDownloadPage() }) {
                            Text(stringResource(Res.string.update_open_download))
                        }
                    }
                is AppUpdateStatus.Ready ->
                    TextButton(onClick = { updates.installAndRestart() }) {
                        Text(stringResource(Res.string.update_install_restart))
                    }
                is AppUpdateStatus.Failed ->
                    TextButton(onClick = { updates.openDownloadPage() }) {
                        Text(stringResource(Res.string.update_open_download))
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

            if (blocked != null) {
                Text(
                    stringResource(Res.string.update_manual_hint, blocked),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                )
            } else {
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
    Text(notes, style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
