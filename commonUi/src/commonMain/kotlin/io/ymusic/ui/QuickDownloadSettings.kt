package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.ymusic.download.DownloadFormat
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.download_save
import io.ymusic.ui.resources.settings_quick_download
import io.ymusic.ui.resources.settings_quick_download_off
import io.ymusic.ui.resources.settings_quick_download_turn_off
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val DEFAULT_QUICK_PRESET = QuickDownloadPreset(DownloadFormat.M4A, 1080, audioLanguage = null, captionLanguage = null)

/** Settings → Downloads → Quick download: edit, save or turn off the persisted preset. */
@Composable
internal fun QuickDownloadRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val saved by graph.quickDownload.collectAsState()
    var draft by remember { mutableStateOf<QuickDownloadPreset?>(null) }
    val title = stringResource(Res.string.settings_quick_download)
    SettingRow(title, saved?.let { presetLabel(it) } ?: stringResource(Res.string.settings_quick_download_off)) {
        draft = saved ?: DEFAULT_QUICK_PRESET
    }
    val editing = draft ?: return
    val languages = rememberYtLanguages(graph)
    fun save(preset: QuickDownloadPreset?) {
        scope.launch { graph.saveQuickDownload(preset) }
        draft = null
    }
    ElevatedBarDialog(
        title = title,
        onDismiss = { draft = null },
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            if (saved != null) {
                TextButton(onClick = { save(null) }) { Text(stringResource(Res.string.settings_quick_download_turn_off)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { draft = null }) { Text(stringResource(Res.string.dialog_cancel)) }
            TextButton(onClick = { save(editing) }) { Text(stringResource(Res.string.download_save)) }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        ) {
            QuickPresetFields(editing, languages, graph.downloads?.ffmpegAvailable == true) { draft = it }
        }
    }
}
