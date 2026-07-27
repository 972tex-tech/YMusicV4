package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtLocaleOption
import ymusic.dynamicengine.yt.YtLocales
import ymusic.dynamicengine.yt.ytLocaleOptions
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.settings.put
import ymusic.ui.resources.Res
import ymusic.ui.resources.dialog_cancel
import ymusic.ui.resources.settings_content_auto_detected
import ymusic.ui.resources.settings_content_language
import ymusic.ui.resources.settings_content_locale_hint
import ymusic.ui.resources.settings_content_region
import ymusic.ui.resources.settings_language
import ymusic.ui.resources.settings_theme
import ymusic.ui.resources.settings_theme_dark
import ymusic.ui.resources.settings_theme_light
import ymusic.ui.resources.settings_theme_system

@Composable
fun SettingsScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val uiLanguage by remember(graph) { graph.settings.flow(AppSettings.uiLanguage) }.collectAsState()
    val theme by remember(graph) { graph.settings.flow(AppSettings.theme) }.collectAsState()
    val contentLanguage by remember(graph) { graph.settings.flow(AppSettings.hl) }.collectAsState()
    val contentRegion by remember(graph) { graph.settings.flow(AppSettings.gl) }.collectAsState()
    val ctx = rememberYtContext(graph)
    // The pickable menus are YouTube's own, labelled in the language we currently ask for — so they
    // are re-fetched when that changes. Until they land (or if the fetch fails) the rows still show
    // the active choice; they just can't offer alternatives.
    var options by remember { mutableStateOf(YtLocales("", "")) }
    var loadingOptions by remember { mutableStateOf(true) }
    LaunchedEffect(graph.engines, ctx) {
        loadingOptions = true
        runCatching { graph.engines.ytLocaleOptions(ctx) }.onSuccess { options = it }
        loadingOptions = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        LanguageRow(
            current = uiLanguage,
            onSelect = { tag -> scope.launch { graph.settings.put(AppSettings.uiLanguage, tag) } },
        )
        ThemeRow(
            current = theme,
            onSelect = { value -> scope.launch { graph.settings.put(AppSettings.theme, value) } },
        )
        ContentLocaleRow(
            title = stringResource(Res.string.settings_content_language),
            current = contentLanguage,
            automatic = ctx.hl,
            options = options.languages,
            loading = loadingOptions,
            onSelect = { code -> scope.launch { graph.settings.put(AppSettings.hl, code) } },
        )
        ContentLocaleRow(
            title = stringResource(Res.string.settings_content_region),
            current = contentRegion,
            automatic = ctx.gl,
            options = options.regions,
            loading = loadingOptions,
            onSelect = { code -> scope.launch { graph.settings.put(AppSettings.gl, code) } },
        )
        Text(
            stringResource(Res.string.settings_content_locale_hint),
            style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

/**
 * A content-language / content-region picker. [current] is the user's stored override ("" = follow
 * the automatic pick); [automatic] is the code that pick currently resolves to, shown next to the
 * automatic entry so it is never a mystery which region the feeds are actually coming from.
 * The menus run to ~100 entries, so picking happens in a dialog, not a dropdown.
 */
@Composable
private fun ContentLocaleRow(
    title: String,
    current: String,
    automatic: String,
    options: List<YtLocaleOption>,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun labelOf(code: String) = options.firstOrNull { it.code.equals(code, ignoreCase = true) }?.label ?: code
    val autoLabel = stringResource(Res.string.settings_content_auto_detected, labelOf(automatic))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.weight(1f),
        )
        Text(
            if (current.isBlank()) autoLabel else labelOf(current),
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        )
    }
    if (open) {
        LocalePickerDialog(
            title = title,
            entries = listOf(YtLocaleOption("", autoLabel)) + options,
            current = current,
            loading = loading,
            onSelect = { code -> onSelect(code); open = false },
            onDismiss = { open = false },
        )
    }
}

/**
 * A scrollable single-choice dialog, opened on the row that shows the current choice. While the
 * menus are still being fetched and there is nothing to offer beyond Automatic, a spinner says so
 * (a stale-but-usable list from the previous locale is shown instead of the spinner). The scroll
 * region sits between the fixed title and buttons, so it gets its own surface tone — the chrome
 * must not blend into the list into one flat color — plus edge dividers whenever content continues
 * past an edge, so a cut-off list is never mistaken for a complete one.
 */
@Composable
private fun LocalePickerDialog(
    title: String,
    entries: List<YtLocaleOption>,
    current: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun isCurrent(code: String) = if (code.isEmpty()) current.isBlank() else code.equals(current, ignoreCase = true)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = entries.indexOfFirst { isCurrent(it.code) }.coerceAtLeast(0),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignColors.SurfaceElevated,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(DesignColors.Surface),
            ) {
                HorizontalDivider(
                    color = if (listState.canScrollBackward) DesignColors.Divider else Color.Transparent,
                )
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries, key = { it.code }) { option ->
                        val selected = isCurrent(option.code)
                        Text(
                            option.label,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.code) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                        )
                    }
                    if (loading && entries.size <= 1) {
                        item("loading") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = DesignColors.Accent)
                            }
                        }
                    }
                }
                HorizontalDivider(
                    color = if (listState.canScrollForward) DesignColors.Divider else Color.Transparent,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
    )
}

/** "" = follow system, per [AppSettings.theme]. */
private val ThemeOptions: List<Pair<String, StringResource>> = listOf(
    "" to Res.string.settings_theme_system,
    "light" to Res.string.settings_theme_light,
    "dark" to Res.string.settings_theme_dark,
)

@Composable
private fun ThemeRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.settings_theme),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource((ThemeOptions.firstOrNull { it.first == current } ?: ThemeOptions.first()).second),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThemeOptions.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}

@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val systemLabel = stringResource(UiLanguageSystem)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.settings_language),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                UiLanguages.firstOrNull { it.tag == current }?.nativeName ?: systemLabel,
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(systemLabel) }, onClick = { onSelect(""); open = false })
            UiLanguages.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.nativeName) }, onClick = { onSelect(lang.tag); open = false })
            }
        }
    }
}
