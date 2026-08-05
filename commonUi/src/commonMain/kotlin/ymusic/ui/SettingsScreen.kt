package ymusic.ui

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtLocaleOption
import ymusic.dynamicengine.yt.YtLocales
import ymusic.dynamicengine.yt.applyYtLocale
import ymusic.dynamicengine.yt.ytLocaleOptions
import ymusic.settings.AppSettings
import ymusic.settings.flow
import ymusic.settings.put
import ymusic.ui.resources.Res
import ymusic.ui.resources.dialog_cancel
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
    // The content locale LIVES in the engine; this screen has exactly two jobs: ask the engine for
    // the region menu + the current pick (one call, when the screen opens and again after a pick),
    // and report picks with applyYtLocale. There is ONE language: the app language doubles as the
    // content language (hl, resolved from the picker's supported list), so only the region has its
    // own YouTube-served picker. Until the reply lands (or if it fails) the region row still shows
    // Automatic; it just can't offer alternatives yet.
    var options by remember { mutableStateOf(YtLocales()) }
    var loadingOptions by remember { mutableStateOf(true) }
    var localeRevision by remember { mutableStateOf(0) }
    LaunchedEffect(graph.engines, localeRevision) {
        loadingOptions = true
        runCatching { graph.engines.ytLocaleOptions() }.onSuccess { options = it }
        loadingOptions = false
    }
    fun pickLocale(gl: String) {
        scope.launch {
            runCatching { graph.engines.applyYtLocale(gl) }
            localeRevision++ // re-ask: the labels and the automatic answer follow the new locale
            graph.localeEpoch.value++ // locale-shaped caches (Discover feeds) refetch
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        LanguageRow(
            current = uiLanguage.ifBlank { defaultUiLanguageTag() },
            onSelect = { tag ->
                scope.launch {
                    // Persist FIRST: apply is only the notify — the engine re-pulls this setting.
                    graph.settings.put(AppSettings.uiLanguage, tag)
                    pickLocale(gl = options.gl)
                }
            },
        )
        ThemeRow(
            current = theme,
            onSelect = { value -> scope.launch { graph.settings.put(AppSettings.theme, value) } },
        )
        ContentLocaleRow(
            title = stringResource(Res.string.settings_content_region),
            current = options.gl,
            options = options.regions,
            loading = loadingOptions,
            onSelect = { code -> pickLocale(gl = code) },
        )
        Text(
            stringResource(Res.string.settings_content_locale_hint),
            style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        DownloadSettingsSection(graph)
    }
}

/**
 * The content-region picker. [current] is the WORKING region — YouTube's own answer, whether
 * IP-derived or following a pick, so there is no separate "automatic" entry: the row always shows
 * a concrete region and selecting one is a plain write. The menu runs to ~100 entries, so picking
 * happens in a dialog, not a dropdown.
 */
@Composable
private fun ContentLocaleRow(
    title: String,
    current: String,
    options: List<YtLocaleOption>,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun labelOf(code: String) = options.firstOrNull { it.code.equals(code, ignoreCase = true) }?.label ?: code
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
            labelOf(current),
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
        )
    }
    if (open) {
        LocalePickerDialog(
            title = title,
            entries = options,
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
 * (a stale-but-usable list from the previous locale is shown instead of the spinner). Custom
 * layout, not [AlertDialog]'s slots (those inset the content into a floating panel): the list is
 * full-bleed on [DesignPalette.Surface], and the fixed title/button bars sit on
 * [DesignPalette.SurfaceElevated] behind a divider, casting a shadow onto the list while content
 * continues past their edge — the ElevatedBarColumn idiom.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LocalePickerDialog(
    title: String,
    entries: List<YtLocaleOption>,
    current: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun isCurrent(code: String) = code.equals(current, ignoreCase = true)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = entries.indexOfFirst { isCurrent(it.code) }.coerceAtLeast(0),
    )
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = DesignColors.Surface,
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
        ) {
            Column {
                val topShadow by animateDpAsState(if (listState.canScrollBackward) 6.dp else 0.dp)
                Column(Modifier.fillMaxWidth().zIndex(1f).shadow(topShadow).background(DesignColors.SurfaceElevated)) {
                    Text(
                        title,
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                    HorizontalDivider(color = DesignColors.Divider)
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
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
                                .padding(horizontal = 24.dp, vertical = 12.dp),
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
                val bottomShadow by animateDpAsState(if (listState.canScrollForward) 6.dp else 0.dp)
                Column(Modifier.fillMaxWidth().zIndex(1f).shadow(bottomShadow).background(DesignColors.SurfaceElevated)) {
                    HorizontalDivider(color = DesignColors.Divider)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
                    }
                }
            }
        }
    }
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
        // The menu anchors to the value text, so it drops on the right under the current choice —
        // anchored to the row it would open at the far-away left edge of the screen.
        Box {
            Text(
                stringResource((ThemeOptions.firstOrNull { it.first == current } ?: ThemeOptions.first()).second),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ThemeOptions.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}

/** ONE language, always a concrete pick (the first launch seeded it) — no "system" entry. */
@Composable
private fun LanguageRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
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
        // Anchored to the value text — see ThemeRow.
        Box {
            Text(
                UiLanguages.firstOrNull { it.tag == current }?.nativeName ?: current,
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                UiLanguages.forEach { lang ->
                    DropdownMenuItem(text = { Text(lang.nativeName) }, onClick = { onSelect(lang.tag); open = false })
                }
            }
        }
    }
}
