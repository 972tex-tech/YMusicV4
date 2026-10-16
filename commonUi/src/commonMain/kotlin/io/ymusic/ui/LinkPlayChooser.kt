package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.menu_play
import io.ymusic.ui.resources.menu_play_single
import io.ymusic.ui.resources.queue_last
import io.ymusic.ui.resources.queue_next
import org.jetbrains.compose.resources.stringResource

/**
 * How to play a video shared to the app (Android's "Play" share target): play it with its radio,
 * alone, or queue it next / last. [onPick] gets null on cancel. Self-contained for a shell activity
 * that has no [App] around it: language and theme come from [settings].
 */
@Composable
fun LinkPlayChooser(settings: SettingsStore, onPick: (LinkAction?) -> Unit) {
    val tag = settings.flow(AppSettings.uiLanguage).value.ifBlank { defaultUiLanguageTag() }
    remember(tag) { applyUiLocale(tag) }
    val palette = designPaletteFor(
        settings.flow(AppSettings.theme).value,
        androidx.compose.foundation.isSystemInDarkTheme(),
        settings.flow(AppSettings.themeAccent).value,
    )
    YMusicTheme(palette) {
        ElevatedBarDialog(
            title = stringResource(Res.string.menu_play),
            onDismiss = { onPick(null) },
            canScrollBackward = false,
            canScrollForward = false,
            buttons = { DialogSecondaryButton(stringResource(Res.string.dialog_cancel)) { onPick(null) } },
        ) {
            Column {
                ChooserRow(stringResource(Res.string.menu_play)) { onPick(LinkAction.OPEN) }
                ChooserRow(stringResource(Res.string.menu_play_single)) { onPick(LinkAction.PLAY_SINGLE) }
                ChooserRow(stringResource(Res.string.queue_next)) { onPick(LinkAction.QUEUE_NEXT) }
                ChooserRow(stringResource(Res.string.queue_last)) { onPick(LinkAction.QUEUE_LAST) }
            }
        }
    }
}

@Composable
private fun ChooserRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
    )
}
