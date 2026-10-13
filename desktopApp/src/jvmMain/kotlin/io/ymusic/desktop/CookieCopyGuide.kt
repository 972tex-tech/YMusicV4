package io.ymusic.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.ActionIcon
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.FeedChip
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_signin_paste_guide
import io.ymusic.ui.resources.cd_signin_paste_pause
import io.ymusic.ui.resources.cd_signin_paste_play
import io.ymusic.ui.resources.signin_paste_browser_chrome
import io.ymusic.ui.resources.signin_paste_browser_firefox
import io.ymusic.ui.resources.signin_paste_browser_safari
import io.ymusic.ui.resources.signin_paste_guide_address
import io.ymusic.ui.resources.signin_paste_guide_copy
import io.ymusic.ui.resources.signin_paste_guide_f12
import io.ymusic.ui.resources.signin_paste_guide_inspector
import io.ymusic.ui.resources.signin_paste_guide_network
import io.ymusic.ui.resources.signin_paste_guide_request
import io.ymusic.ui.resources.signin_paste_guide_right_click
import io.ymusic.ui.resources.signin_paste_hint
import io.ymusic.ui.resources.signin_paste_hint_firefox
import io.ymusic.ui.resources.signin_paste_hint_safari
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class CookieCopyBeat(
    val durationMs: Int,
    val moveStart: Float,
    val moveEnd: Float,
) {
    AddressBar(4000, 0f, 0.34f),
    DevTools(3600, 0f, 0.01f),
    Network(3600, 0f, 0.36f),
    Request(4400, 0.22f, 0.55f),
    Copy(5200, 0f, 0.28f),
}

private enum class CookieCopyBrowser {
    Chrome,
    Firefox,
    Safari,
}

private val CookieCopyBrowser.label: StringResource
    get() = when (this) {
        CookieCopyBrowser.Chrome -> Res.string.signin_paste_browser_chrome
        CookieCopyBrowser.Firefox -> Res.string.signin_paste_browser_firefox
        CookieCopyBrowser.Safari -> Res.string.signin_paste_browser_safari
    }

private val CookieCopyBrowser.hint: StringResource
    get() = when (this) {
        CookieCopyBrowser.Chrome -> Res.string.signin_paste_hint
        CookieCopyBrowser.Firefox -> Res.string.signin_paste_hint_firefox
        CookieCopyBrowser.Safari -> Res.string.signin_paste_hint_safari
    }

private val CookieCopyBrowser.devtoolsKey: String
    get() = if (this == CookieCopyBrowser.Safari) "⌥⌘I" else "F12"

/** Light schematic chrome — stays light in the dark theme so it still reads as a browser window. */
private object CookieCopyChrome {
    val Frame = Color(0xFFE4E6EA)
    val Surface = Color(0xFFFFFFFF)
    val TabIdle = Color(0xFFD5D8DE)
    val Address = Color(0xFFF1F3F4)
    val Page = Color(0xFFF6F6F6)
    val Header = Color(0xFFC62828)
    val Tools = Color(0xFFEEF0F3)
    val ToolsLine = Color(0xFFD0D4DA)
    val Text = Color(0xFF3C4043)
    val TextMuted = Color(0xFF80868B)
}

private class CookieCopyAnchors {
    var window: LayoutCoordinates? = null
    var address = Offset(0.48f, 0.185f)
    var application = Offset(0.58f, 0.50f)
    var cookiesOrigin = Offset(0.17f, 0.80f)

    fun target(beat: CookieCopyBeat): Offset = when (beat) {
        CookieCopyBeat.AddressBar, CookieCopyBeat.DevTools -> address
        CookieCopyBeat.Network -> application
        CookieCopyBeat.Request, CookieCopyBeat.Copy -> cookiesOrigin
    }
}

@Composable
internal fun CookieCopyGuide(modifier: Modifier = Modifier) {
    val cd = stringResource(Res.string.cd_signin_paste_guide)
    val pauseCd = stringResource(Res.string.cd_signin_paste_pause)
    val playCd = stringResource(Res.string.cd_signin_paste_play)
    var browser by remember { mutableStateOf(CookieCopyBrowser.Chrome) }
    var paused by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var stepEpoch by remember { mutableIntStateOf(0) }
    var beat by remember { mutableStateOf(CookieCopyBeat.AddressBar) }
    var progress by remember { mutableStateOf(0f) }
    var cursor by remember { mutableStateOf(Offset(0.78f, 0.46f)) }
    val anchors = remember { CookieCopyAnchors() }
    val steps = CookieCopyBeat.entries

    LaunchedEffect(browser, stepIndex, stepEpoch) {
        val b = steps[stepIndex]
        beat = b
        progress = 0f
        val from = if (stepIndex == 0) Offset(0.78f, 0.46f) else anchors.target(steps[stepIndex - 1])
        cursor = from
        var startNs = withFrameNanos { it }
        var pausedAcc = 0L
        var pauseAt = 0L
        val durNs = b.durationMs * 1_000_000L
        var dest = anchors.target(b)
        while (isActive) {
            val now = withFrameNanos { it }
            if (paused) {
                if (pauseAt == 0L) pauseAt = now
                continue
            }
            if (pauseAt != 0L) {
                pausedAcc += now - pauseAt
                pauseAt = 0L
            }
            val t = ((now - startNs - pausedAcc).toFloat() / durNs).coerceAtMost(1f)
            progress = t
            dest = anchors.target(b)
            val mt = FastOutSlowInEasing.transform(
                ((t - b.moveStart) / (b.moveEnd - b.moveStart)).coerceIn(0f, 1f),
            )
            cursor = lerp(from, dest, mt)
            if (t >= 1f) break
        }
        if (isActive) stepIndex = (stepIndex + 1) % steps.size
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                CookieCopyBrowser.entries.forEach { b ->
                    FeedChip(
                        title = stringResource(b.label),
                        selected = browser == b,
                        onClick = {
                            browser = b
                            paused = false
                            stepIndex = 0
                            stepEpoch++
                        },
                    )
                }
            }
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
            ) {
                // Browser chrome is LTR; flipping the schematic in Arabic would mis-teach the click path.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    CookieCopyWindow(
                        browser = browser,
                        beat = beat,
                        progress = progress,
                        cursor = cursor,
                        anchors = anchors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clearAndSetSemantics { contentDescription = cd },
                    )
                }
                AnimatedContent(
                    targetState = beat to browser,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { (b, br) ->
                    Text(
                        stringResource(b.caption(br)),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = DesignColors.Ink,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Row(
            Modifier.widthIn(max = 380.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionIcon(
                if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                if (paused) playCd else pauseCd,
                iconSize = 20.dp,
                hitSize = 36.dp,
                onClick = { paused = !paused },
            )
            Row(
                Modifier.weight(1f).height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                steps.forEachIndexed { i, step ->
                    val fill = when {
                        i < stepIndex -> 1f
                        i == stepIndex -> progress
                        else -> 0f
                    }
                    val stepCd = stringResource(step.caption(browser))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                stepIndex = i
                                stepEpoch++
                            }
                            .semantics { contentDescription = stepCd },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(DesignColors.OutlineSoft),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fill)
                                    .background(DesignColors.Accent),
                            )
                        }
                    }
                }
            }
        }
        Text(
            stringResource(browser.hint),
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun CookieCopyBeat.caption(browser: CookieCopyBrowser): StringResource = when (this) {
    CookieCopyBeat.AddressBar -> Res.string.signin_paste_guide_address
    CookieCopyBeat.DevTools -> when (browser) {
        CookieCopyBrowser.Safari -> Res.string.signin_paste_guide_inspector
        else -> Res.string.signin_paste_guide_f12
    }
    CookieCopyBeat.Network -> Res.string.signin_paste_guide_network
    CookieCopyBeat.Request -> Res.string.signin_paste_guide_request
    CookieCopyBeat.Copy -> Res.string.signin_paste_guide_copy
}

@Composable
private fun CookieCopyWindow(
    browser: CookieCopyBrowser,
    beat: CookieCopyBeat,
    progress: Float,
    cursor: Offset,
    anchors: CookieCopyAnchors,
    modifier: Modifier = Modifier,
) {
    val accent = DesignColors.Accent
    val density = LocalDensity.current
    val toolsOpen = when (beat) {
        CookieCopyBeat.AddressBar -> 0f
        CookieCopyBeat.DevTools -> FastOutSlowInEasing.transform(
            ((progress - 0.28f) / 0.50f).coerceIn(0f, 1f),
        )
        else -> 1f
    }
    val addressOn = beat == CookieCopyBeat.AddressBar && progress > 0.28f
    val networkOn = beat >= CookieCopyBeat.Network ||
        (beat == CookieCopyBeat.DevTools && progress > 0.55f)
    val originOn = beat >= CookieCopyBeat.Request &&
        (beat != CookieCopyBeat.Request || progress > 0.45f)
    val tableOn = beat == CookieCopyBeat.Copy && progress > 0.52f
    val showF12 = beat == CookieCopyBeat.DevTools && progress < 0.78f
    val showReload = beat == CookieCopyBeat.Request && progress < 0.45f
    val showCopyCurl = beat == CookieCopyBeat.Copy && progress in 0.18f..0.62f
    val pulse = 0.5f + 0.5f * sin(progress * 6.0 * PI).toFloat()
    val clickScale = when {
        beat == CookieCopyBeat.AddressBar && progress in 0.42f..0.56f -> 0.86f
        beat == CookieCopyBeat.Network && progress in 0.38f..0.52f -> 0.86f
        beat == CookieCopyBeat.Request && progress in 0.52f..0.66f -> 0.86f
        beat == CookieCopyBeat.Copy && progress in 0.38f..0.52f -> 0.86f
        else -> 1f
    }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier
            .clip(shape)
            .border(1.dp, DesignColors.Outline, shape)
            .background(CookieCopyChrome.Surface)
            .onGloballyPositioned { anchors.window = it },
    ) {
        Column(Modifier.fillMaxSize()) {
            CookieCopyTabs()
            CookieCopyToolbar(
                focused = addressOn,
                pulse = pulse,
                accent = accent,
                modifier = Modifier.trackAnchor(anchors) { anchors.address = it },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CookieCopyPage()
            }
            if (toolsOpen > 0f) {
                CookieCopyDevTools(
                    networkOn = networkOn,
                    originOn = originOn,
                    pulse = pulse,
                    accent = accent,
                    anchors = anchors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp * toolsOpen)
                        .clipToBounds(),
                )
            }
        }
        if (tableOn) {
            CookieCopyCurlMenu(
                pulse = pulse,
                accent = accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val win = anchors.window?.size ?: return@offset IntOffset.Zero
                        val pad = with(density) { 6.dp.roundToPx() }
                        val menuW = with(density) { 128.dp.roundToPx() }
                        val menuH = with(density) { 58.dp.roundToPx() }
                        val cx = cursor.x * win.width + pad
                        val cy = cursor.y * win.height + pad
                        val x = cx.roundToInt().coerceIn(pad, (win.width - menuW - pad).coerceAtLeast(pad))
                        val y = if (cy + menuH > win.height - pad) {
                            (cursor.y * win.height - menuH - pad).roundToInt()
                        } else {
                            cy.roundToInt()
                        }.coerceAtLeast(pad)
                        IntOffset(x, y)
                    },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset(cursor.x * size.width, cursor.y * size.height)
            drawCursor(origin, clickScale, accent)
        }
        KeyChip(
            label = browser.devtoolsKey,
            visible = showF12,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
        )
        KeyChip(
            label = "F5",
            visible = showReload,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
        )
        KeyChip(
            label = stringResource(Res.string.signin_paste_guide_right_click),
            visible = showCopyCurl,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp, end = 12.dp),
        )
    }
}

@Composable
private fun CookieCopyTabs() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(CookieCopyChrome.Frame)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(CookieCopyChrome.Surface)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(CookieCopyChrome.Header))
            Text(
                "YouTube",
                style = TextStyle(fontSize = 8.sp, color = CookieCopyChrome.Text),
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(CookieCopyChrome.TabIdle),
        )
    }
}

@Composable
private fun CookieCopyToolbar(
    focused: Boolean,
    pulse: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(CookieCopyChrome.Surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("←", style = TextStyle(fontSize = 10.sp, color = CookieCopyChrome.TextMuted))
        Text("→", style = TextStyle(fontSize = 10.sp, color = CookieCopyChrome.TextMuted))
        Text("↻", style = TextStyle(fontSize = 10.sp, color = CookieCopyChrome.TextMuted))
        Row(
            modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(CookieCopyChrome.Address)
                .border(
                    width = if (focused) 1.5.dp else 1.dp,
                    color = if (focused) accent.copy(alpha = 0.45f + 0.55f * pulse) else CookieCopyChrome.ToolsLine,
                    shape = RoundedCornerShape(9.dp),
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "youtube.com",
                style = TextStyle(fontSize = 9.sp, color = CookieCopyChrome.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CookieCopyPage() {
    Column(Modifier.fillMaxSize().background(CookieCopyChrome.Page)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(CookieCopyChrome.Header))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val play = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.22f)
                    lineTo(size.width * 0.32f, size.height * 0.78f)
                    lineTo(size.width * 0.78f, size.height * 0.50f)
                    close()
                }
                drawPath(play, CookieCopyChrome.Header)
            }
        }
    }
}

@Composable
private fun CookieCopyDevTools(
    networkOn: Boolean,
    originOn: Boolean,
    pulse: Float,
    accent: Color,
    anchors: CookieCopyAnchors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(CookieCopyChrome.Tools)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolsTab("Elements", selected = !networkOn)
            ToolsTab(
                label = "Network",
                selected = networkOn,
                pulse = if (networkOn) pulse else 0f,
                accent = accent,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .trackAnchor(anchors) { anchors.application = it },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CookieCopyChrome.ToolsLine))
        CookieCopyNetworkPane(
            originOn = originOn,
            pulse = pulse,
            accent = accent,
            anchors = anchors,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun CookieCopyNetworkPane(
    originOn: Boolean,
    pulse: Float,
    accent: Color,
    anchors: CookieCopyAnchors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(CookieCopyChrome.Surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val head = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = CookieCopyChrome.TextMuted)
            Text("Name", style = head, modifier = Modifier.weight(1f), maxLines = 1)
            Text("Status", style = head, maxLines = 1)
            Text("Type", style = head, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CookieCopyChrome.ToolsLine))
        NetworkRequestRow(
            name = "generate_204",
            status = "204",
            type = "xhr",
            selected = originOn,
            pulse = if (originOn) pulse else 0f,
            accent = accent,
            modifier = Modifier.trackAnchor(anchors) { anchors.cookiesOrigin = it },
        )
        NetworkRequestRow(name = "getDatasyncIdsEndpoint", status = "200", type = "fetch", accent = accent)
        NetworkRequestRow(name = "base.js", status = "200", type = "script", accent = accent)
    }
}

@Composable
private fun NetworkRequestRow(
    name: String,
    status: String,
    type: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    pulse: Float = 0f,
    accent: Color = DesignColors.Accent,
) {
    val wash = if (selected) accent.copy(alpha = 0.22f + 0.12f * pulse) else Color.Transparent
    val color = if (selected) accent else CookieCopyChrome.Text
    val style = TextStyle(
        fontSize = 8.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(wash)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, style = style, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, style = style, maxLines = 1)
        Text(type, style = style, maxLines = 1)
    }
}

@Composable
private fun CookieCopyCurlMenu(
    pulse: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(128.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CookieCopyChrome.Surface)
            .border(1.dp, CookieCopyChrome.ToolsLine, RoundedCornerShape(4.dp))
            .padding(vertical = 3.dp),
    ) {
        CurlMenuRow("Copy")
        CurlMenuRow("Copy as cURL", selected = true, pulse = pulse, accent = accent)
        CurlMenuRow("Copy as fetch")
    }
}

@Composable
private fun CurlMenuRow(
    label: String,
    selected: Boolean = false,
    pulse: Float = 0f,
    accent: Color = CookieCopyChrome.Text,
) {
    Text(
        label,
        style = TextStyle(
            fontSize = 8.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else CookieCopyChrome.Text,
        ),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.22f + 0.12f * pulse) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ToolsTab(
    label: String,
    selected: Boolean,
    pulse: Float = 0f,
    accent: Color = CookieCopyChrome.Text,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) accent else CookieCopyChrome.TextMuted
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) accent.copy(alpha = 0.22f + 0.14f * pulse) else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) accent else Color.Transparent),
        )
    }
}

@Composable
private fun KeyChip(label: String, visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(160))
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .background(DesignColors.Accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            ),
        )
    }
}

private fun Modifier.trackAnchor(
    anchors: CookieCopyAnchors,
    set: (Offset) -> Unit,
): Modifier = onGloballyPositioned { coords ->
    val win = anchors.window ?: return@onGloballyPositioned
    if (!win.isAttached) return@onGloballyPositioned
    val b = coords.boundsInRoot()
    val w = win.boundsInRoot()
    if (w.width < 1f || w.height < 1f) return@onGloballyPositioned
    set(
        Offset(
            ((b.left + b.width / 2f) - w.left) / w.width,
            ((b.top + b.height / 2f) - w.top) / w.height,
        ),
    )
}

private fun DrawScope.drawCursor(
    origin: Offset,
    scale: Float,
    fill: Color,
) {
    val s = 11.dp.toPx() * scale
    val path = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + s, origin.y + s * 1.05f)
        lineTo(origin.x + s * 0.42f, origin.y + s * 1.05f)
        lineTo(origin.x + s * 0.68f, origin.y + s * 1.72f)
        lineTo(origin.x + s * 0.40f, origin.y + s * 1.72f)
        lineTo(origin.x + s * 0.18f, origin.y + s * 1.05f)
        lineTo(origin.x - s * 0.28f, origin.y + s * 1.05f)
        close()
    }
    translate(1.6f, 1.6f) { drawPath(path, Color.Black.copy(alpha = 0.22f)) }
    drawPath(path, fill)
    drawPath(
        path,
        Color.White,
        style = Stroke(width = 1.2.dp.toPx(), join = StrokeJoin.Round),
    )
}
