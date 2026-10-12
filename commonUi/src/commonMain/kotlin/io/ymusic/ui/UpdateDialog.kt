package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.update_ask_later
import io.ymusic.ui.resources.update_available_title
import io.ymusic.ui.resources.update_background
import io.ymusic.ui.resources.update_check
import io.ymusic.ui.resources.update_check_failed
import io.ymusic.ui.resources.update_checking
import io.ymusic.ui.resources.update_close
import io.ymusic.ui.resources.update_download
import io.ymusic.ui.resources.update_download_failed
import io.ymusic.ui.resources.update_downloading
import io.ymusic.ui.resources.update_failed
import io.ymusic.ui.resources.update_full_changelog
import io.ymusic.ui.resources.update_install_now
import io.ymusic.ui.resources.update_manual_hint
import io.ymusic.ui.resources.update_ready
import io.ymusic.ui.resources.update_up_to_date
import io.ymusic.ui.resources.update_version
import org.jetbrains.compose.resources.stringResource

/**
 * The update prompt: the version, its brief notes and a link to the full changelog.
 *
 * - Available: "Ask later" / "Download". Download starts the download with progress here; when this
 *   release cannot replace the installed copy (`installInApp` false, or self-update blocked) it
 *   opens the release's web page instead.
 * - Downloading (started here): "Background" hides the dialog and lets the download go on;
 *   "Install now" stays disabled until it finishes.
 * - Ready: "Ready to install" beside the version; "Ask later" / "Install now". A download hidden or
 *   finished in the background brings the dialog back here — nothing installs without a tap.
 *
 * "Ask later" hides it for the rest of this run (the update itself stays offered: the drawer still
 * flags it); the next cold start or a "Check for update" asks again.
 */
@Composable
fun UpdateDialog(graph: AppGraph, recheck: Int = 0, showMessage: (String) -> Unit = {}) {
    val updates = graph.updates ?: return
    val status by updates.status.collectAsState()
    val uriHandler = LocalUriHandler.current
    // Keyed on [recheck]: a manual "Check for update" brings back a release asked about later.
    var dismissedVersion by remember(recheck) { mutableStateOf<Int?>(null) }
    var failureDismissed by remember(recheck) { mutableStateOf(false) }
    // The download was started here and not sent to the background: show its progress. A manual
    // "Check for update" ([recheck]) that lands on an automatic download in flight shows it too.
    var watching by remember(recheck) {
        mutableStateOf(recheck > 0 && updates.status.value is AppUpdateStatus.Downloading)
    }
    // Sent to the background: a failure there is a short message, not this dialog; the next launch
    // offers the update afresh.
    var backgrounded by remember(recheck) { mutableStateOf(false) }
    val failedMessage = stringResource(Res.string.update_download_failed)
    LaunchedEffect(status) {
        when {
            status is AppUpdateStatus.Ready -> backgrounded = false
            backgrounded && status is AppUpdateStatus.Failed -> {
                backgrounded = false
                failureDismissed = true
                showMessage(failedMessage)
            }
        }
    }

    val versionCode = when (val s = status) {
        is AppUpdateStatus.Available -> s.versionCode
        is AppUpdateStatus.Downloading -> s.versionCode
        is AppUpdateStatus.Ready -> s.versionCode
        else -> null
    }
    when (status) {
        AppUpdateStatus.None -> return
        is AppUpdateStatus.Failed -> if (failureDismissed || backgrounded) return
        is AppUpdateStatus.Downloading -> if (!watching || versionCode == dismissedVersion) return
        else -> if (versionCode == dismissedVersion) return
    }

    val blocked = updates.blockedReason
    val inPlace = blocked == null && (status as? AppUpdateStatus.Available)?.installInApp != false
    val changelogUrl = when (val s = status) {
        is AppUpdateStatus.Available -> s.changelogUrl
        is AppUpdateStatus.Ready -> s.changelogUrl
        else -> null
    }
    val askLater: () -> Unit = {
        if (status is AppUpdateStatus.Failed) failureDismissed = true else dismissedVersion = versionCode
        watching = false
    }
    val background: () -> Unit = {
        watching = false
        backgrounded = true
    }

    ElevatedBarDialog(
        title = stringResource(Res.string.update_available_title),
        // Back acts as the first button; a tap outside does nothing — only the buttons close it.
        onDismiss = if (status is AppUpdateStatus.Downloading) background else askLater,
        dismissOnClickOutside = false,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            when (status) {
                is AppUpdateStatus.Available -> {
                    DialogSecondaryButton(stringResource(Res.string.update_ask_later), onClick = askLater)
                    TextButton(
                        onClick = {
                            if (inPlace) {
                                watching = true
                                updates.download()
                            } else {
                                updates.openDownloadPage()
                            }
                        },
                    ) { Text(stringResource(Res.string.update_download)) }
                }
                is AppUpdateStatus.Downloading -> {
                    DialogSecondaryButton(stringResource(Res.string.update_background), onClick = background)
                    TextButton(onClick = {}, enabled = false) { Text(stringResource(Res.string.update_install_now)) }
                }
                is AppUpdateStatus.Ready -> {
                    DialogSecondaryButton(stringResource(Res.string.update_ask_later), onClick = askLater)
                    TextButton(onClick = { updates.installAndRestart() }) { Text(stringResource(Res.string.update_install_now)) }
                }
                is AppUpdateStatus.Failed -> {
                    DialogSecondaryButton(stringResource(Res.string.update_ask_later), onClick = askLater)
                    TextButton(onClick = { updates.openDownloadPage() }) { Text(stringResource(Res.string.update_download)) }
                }
                AppUpdateStatus.None -> Unit
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.update_version, name),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                    )
                    if (status is AppUpdateStatus.Ready) {
                        Text(
                            stringResource(Res.string.update_ready),
                            style = TextStyle(fontSize = 13.sp, color = DesignColors.Accent),
                        )
                    }
                }
            }
            when (val s = status) {
                is AppUpdateStatus.Available -> ChangeNotes(s.notes)
                is AppUpdateStatus.Ready -> ChangeNotes(s.notes)
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
            }
        }
    }
}

/**
 * "Check for update" from the drawer: checking, then up to date or failed. A found update closes
 * this ([onDone] true) and the update prompt takes over.
 */
@Composable
fun CheckUpdateDialog(graph: AppGraph, onDone: (found: Boolean) -> Unit) {
    val updates = graph.updates ?: return
    var result by remember { mutableStateOf<UpdateCheck?>(null) }
    LaunchedEffect(Unit) {
        val check = updates.checkNow()
        if (check == UpdateCheck.Found) onDone(true) else result = check
    }
    ElevatedBarDialog(
        title = stringResource(Res.string.update_check),
        onDismiss = { onDone(false) },
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.update_close)) { onDone(false) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (result) {
                null -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.update_checking), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
                }
                UpdateCheck.UpToDate -> {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = DesignColors.BrandGreen)
                    Text(stringResource(Res.string.update_up_to_date), style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
                }
                else -> Text(
                    stringResource(Res.string.update_check_failed),
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.InkSecondary),
                )
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
