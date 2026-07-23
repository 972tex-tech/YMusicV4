package ymusic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtPageTab
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_back
import ymusic.ui.resources.cd_clear
import ymusic.ui.resources.cd_filter
import ymusic.ui.resources.cd_menu
import ymusic.ui.resources.cd_more_tabs
import ymusic.ui.resources.cd_search
import ymusic.ui.resources.search_youtube

/*
 * The ONE app-bar language, every scenario (see the "App bars" catalogue group):
 *  - [DesignAppBar]        home shell: menu · title · [inline tabs] · search
 *  - [SearchInputBar]      search input: back · pill text field · clear
 *  - [SearchResultsBar]    search results: back · query · [filter] · search
 *  - [DetailAppBar]        detail pages: back · title · search (+ e.g. [ChannelTabRow] riding it)
 * All bars share [AppBarRow] metrics + [AppBarTitle] type; screens host them in (or as)
 * [ElevatedBarColumn] so the lift behavior is identical everywhere.
 */

/** An app-bar surface that lifts while the screen under it is scrolled: shadow + the elevated
 *  tone (elevated white ≠ white). Drawn above siblings so the shadow is never painted over. */
@Composable
internal fun ElevatedBarColumn(content: @Composable ColumnScope.() -> Unit) {
    val elevated = LocalAppBarElevated.current?.value == true
    val shadow by animateDpAsState(if (elevated) 4.dp else 0.dp)
    val tone by animateColorAsState(if (elevated) DesignColors.SurfaceElevated else DesignColors.Surface)
    Column(Modifier.shadow(shadow).background(tone).zIndex(1f), content = content)
}

/** The bars' shared metrics row. Paddings/gaps shave the ActionIcon hit-inset (5dp) so the glyphs
 *  keep the bar's visual metrics; the 6dp top stands in for the mockup's under-status-bar margin. */
@Composable
internal fun AppBarRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The bar-title text role; [fill] claims the remaining row width (off when inline content follows). */
@Composable
internal fun RowScope.AppBarTitle(text: String, fill: Boolean = true) {
    Text(
        text,
        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (fill) Modifier.weight(1f) else Modifier,
    )
}

/** The home shell's app bar (design/final/home.html): menu · title · [inline tabs on landscape] ·
 *  search. Bare (no elevation wrap) — the shell's ElevatedBarColumn carries it plus the tab row. */
@Composable
internal fun DesignAppBar(
    title: String,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    inlineContent: (@Composable () -> Unit)? = null,
) {
    AppBarRow {
        ActionIcon(Icons.Default.Menu, stringResource(Res.string.cd_menu), onClick = onMenu)
        AppBarTitle(title, fill = false)
        if (inlineContent != null) {
            Box(Modifier.padding(start = 16.dp)) { inlineContent() }
        }
        Box(Modifier.weight(1f))
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
}

/** The search input bar: back · pill field (`.sfield`: gray pill, 13.5sp text, clear ✕ while
 *  non-empty). [autoFocus] off for showcase hosting (the catalogue). */
@Composable
internal fun SearchInputBar(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
) = ElevatedBarColumn {
    val focusRequester = remember { FocusRequester() }
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFFF1F1F1))
                .padding(start = 13.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.5.sp, color = DesignColors.Ink),
                cursorBrush = SolidColor(DesignColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(value.text) }),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                stringResource(Res.string.search_youtube),
                                style = TextStyle(fontSize = 13.5.sp, color = DesignColors.InkFaint),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.text.isNotEmpty()) {
                ActionIcon(
                    Icons.Default.Close,
                    stringResource(Res.string.cd_clear),
                    iconSize = 16.dp,
                    hitSize = 28.dp,
                    tint = DesignColors.InkSecondary,
                    onClick = { onValue(TextFieldValue("")) },
                )
            }
        }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

/** The search results bar: back · submitted query · [filter] · search. */
@Composable
internal fun SearchResultsBar(
    title: String,
    onBack: () -> Unit,
    showFilter: Boolean,
    onFilter: () -> Unit,
    onSearch: () -> Unit,
) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        AppBarTitle(title)
        if (showFilter) {
            ActionIcon(Icons.Outlined.FilterAlt, stringResource(Res.string.cd_filter), onClick = onFilter)
        }
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
}

/** The detail pages' bar: back · title · search; [bottomContent] (the channel tab row) rides
 *  inside the same lifted surface so the scroll shadow falls below it. */
@Composable
internal fun DetailAppBar(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    bottomContent: (@Composable () -> Unit)? = null,
) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        AppBarTitle(title)
        ActionIcon(Icons.Default.Search, stringResource(Res.string.cd_search), onClick = onSearch)
    }
    bottomContent?.invoke()
}

/** Uppercase text tabs with the accent underline; scrolls horizontally when they overflow, with an
 *  edge fade + chevron on the cut-off side(s) (click pages the row — desktop mice can't drag it). */
@Composable
internal fun ChannelTabRow(tabs: List<YtPageTab>, activeUrl: String?, onSelect: (YtPageTab) -> Unit) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // The page was opened by a link that matches a tab → highlight it; opened by any other link
    // (e.g. a bare channel item) → fall back to the server's selected flag.
    val anyMatch = tabs.any { it.url != null && it.url == activeUrl }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            tabs.forEach { tab ->
                val active = if (anyMatch) tab.url == activeUrl else tab.selected
                Column(Modifier.clickable { onSelect(tab) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tab.title.uppercase(),
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) DesignColors.Accent else DesignColors.InkFaint,
                        ),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) DesignColors.Accent else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        if (scrollState.maxValue > 0 && scrollState.value > 0) {
            TabOverflowHint(
                towardStart = true,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { scope.launch { scrollState.animateScrollBy(-TabScrollStep) } },
            )
        }
        if (scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue) {
            TabOverflowHint(
                towardStart = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = { scope.launch { scrollState.animateScrollBy(TabScrollStep) } },
            )
        }
    }
}

private const val TabScrollStep = 240f

/** The cut-off side's affordance: a fade to the surface color under a chevron. */
@Composable
private fun TabOverflowHint(towardStart: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = listOf(Color.White, Color.White.copy(alpha = 0f))
    Box(
        modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(if (towardStart) colors else colors.reversed()))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (towardStart) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
            stringResource(Res.string.cd_more_tabs),
            Modifier.size(20.dp),
            tint = DesignColors.InkSoft,
        )
    }
}
