package io.ymusic.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_signin_paste_guide
import io.ymusic.ui.resources.signin_paste_guide_address
import io.ymusic.ui.resources.signin_paste_guide_application
import io.ymusic.ui.resources.signin_paste_guide_cookies
import io.ymusic.ui.resources.signin_paste_guide_copy
import io.ymusic.ui.resources.signin_paste_guide_f12
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin

private enum class CookieCopyBeat(
    val durationMs: Int,
    val moveStart: Float,
    val moveEnd: Float,
) {
    AddressBar(2000, 0f, 0.34f),
    DevTools(1800, 0f, 0.01f),
    Application(1800, 0f, 0.36f),
    Cookies(2200, 0.22f, 0.55f),
    Copy(2600, 0f, 0.28f),
}

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
    val Row = Color(0xFFF7F8FA)
}

private class CookieCopyAnchors {
    var window: LayoutCoordinates? = null
    var address = Offset(0.48f, 0.185f)
    var application = Offset(0.58f, 0.50f)
    var cookiesOrigin = Offset(0.17f, 0.80f)
    var table = Offset(0.62f, 0.76f)

    fun target(beat: CookieCopyBeat): Offset = when (beat) {
        CookieCopyBeat.AddressBar, CookieCopyBeat.DevTools -> address
        CookieCopyBeat.Application -> application
        CookieCopyBeat.Cookies -> cookiesOrigin
        CookieCopyBeat.Copy -> table
    }
}

@Composable
internal fun CookieCopyGuide(modifier: Modifier = Modifier) {
    val cd = stringResource(Res.string.cd_signin_paste_guide)
    var beat by remember { mutableStateOf(CookieCopyBeat.AddressBar) }
    var progress by remember { mutableStateOf(0f) }
    var cursor by remember { mutableStateOf(Offset(0.78f, 0.46f)) }
    val anchors = remember { CookieCopyAnchors() }

    LaunchedEffect(Unit) {
        var from = Offset(0.78f, 0.46f)
        while (isActive) {
            for (b in CookieCopyBeat.entries) {
                beat = b
                val startNs = withFrameNanos { it }
                val durNs = b.durationMs * 1_000_000L
                var dest = anchors.target(b)
                while (isActive) {
                    val t = ((withFrameNanos { it } - startNs).toFloat() / durNs).coerceAtMost(1f)
                    progress = t
                    dest = anchors.target(b)
                    val mt = FastOutSlowInEasing.transform(
                        ((t - b.moveStart) / (b.moveEnd - b.moveStart)).coerceIn(0f, 1f),
                    )
                    cursor = lerp(from, dest, mt)
                    if (t >= 1f) break
                }
                from = dest
            }
        }
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        AnimatedContent(
            targetState = beat,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { b ->
            Text(
                stringResource(b.caption),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DesignColors.Ink,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Chrome's own UI is LTR; flipping the schematic in Arabic would mis-teach the click path.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            CookieCopyWindow(
                beat = beat,
                progress = progress,
                cursor = cursor,
                anchors = anchors,
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .height(180.dp)
                    .clearAndSetSemantics { contentDescription = cd },
            )
        }
    }
}

private val CookieCopyBeat.caption: StringResource
    get() = when (this) {
        CookieCopyBeat.AddressBar -> Res.string.signin_paste_guide_address
        CookieCopyBeat.DevTools -> Res.string.signin_paste_guide_f12
        CookieCopyBeat.Application -> Res.string.signin_paste_guide_application
        CookieCopyBeat.Cookies -> Res.string.signin_paste_guide_cookies
        CookieCopyBeat.Copy -> Res.string.signin_paste_guide_copy
    }

@Composable
private fun CookieCopyWindow(
    beat: CookieCopyBeat,
    progress: Float,
    cursor: Offset,
    anchors: CookieCopyAnchors,
    modifier: Modifier = Modifier,
) {
    val accent = DesignColors.Accent
    val toolsOpen = when (beat) {
        CookieCopyBeat.AddressBar -> 0f
        CookieCopyBeat.DevTools -> FastOutSlowInEasing.transform(
            ((progress - 0.28f) / 0.50f).coerceIn(0f, 1f),
        )
        else -> 1f
    }
    val addressOn = beat == CookieCopyBeat.AddressBar && progress > 0.28f
    val applicationOn = beat >= CookieCopyBeat.Application
    val cookiesOpen = beat >= CookieCopyBeat.Cookies
    val originOn = beat >= CookieCopyBeat.Cookies &&
        (beat != CookieCopyBeat.Cookies || progress > 0.45f)
    val tableOn = beat == CookieCopyBeat.Copy && progress > 0.22f
    val showF12 = beat == CookieCopyBeat.DevTools && progress < 0.78f
    val showCtrlA = beat == CookieCopyBeat.Copy && progress in 0.18f..0.52f
    val showCtrlC = beat == CookieCopyBeat.Copy && progress > 0.52f
    val pulse = 0.5f + 0.5f * sin(progress * 6.0 * PI).toFloat()
    val clickScale = when {
        beat == CookieCopyBeat.AddressBar && progress in 0.42f..0.56f -> 0.86f
        beat == CookieCopyBeat.Application && progress in 0.38f..0.52f -> 0.86f
        beat == CookieCopyBeat.Cookies && progress in 0.52f..0.66f -> 0.86f
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
                    applicationOn = applicationOn,
                    cookiesOpen = cookiesOpen,
                    originOn = originOn,
                    tableOn = tableOn,
                    pulse = pulse,
                    accent = accent,
                    anchors = anchors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(108.dp * toolsOpen)
                        .clipToBounds(),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset(cursor.x * size.width, cursor.y * size.height)
            drawCursor(origin, clickScale, accent)
        }
        KeyChip(
            label = "F12",
            visible = showF12,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp),
        )
        KeyChip(
            label = "Ctrl+A",
            visible = showCtrlA,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp, end = 20.dp),
        )
        KeyChip(
            label = "Ctrl+C",
            visible = showCtrlC,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp, end = 20.dp),
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
    applicationOn: Boolean,
    cookiesOpen: Boolean,
    originOn: Boolean,
    tableOn: Boolean,
    pulse: Float,
    accent: Color,
    anchors: CookieCopyAnchors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(CookieCopyChrome.Tools)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToolsTab("Elements", selected = !applicationOn)
            ToolsTab("Console", selected = false)
            ToolsTab("Sources", selected = false)
            ToolsTab("Network", selected = false)
            ToolsTab(
                label = "Application",
                selected = applicationOn,
                pulse = if (applicationOn) pulse else 0f,
                accent = accent,
                modifier = Modifier.trackAnchor(anchors) { anchors.application = it },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CookieCopyChrome.ToolsLine))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .width(118.dp)
                    .fillMaxHeight()
                    .background(CookieCopyChrome.Tools)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TreeLine("Storage")
                TreeLine(
                    label = "Cookies",
                    accented = cookiesOpen,
                    pulse = if (cookiesOpen && !originOn) pulse else 0f,
                    accent = accent,
                )
                if (cookiesOpen) {
                    TreeLine(
                        label = "https://www.youtube.com",
                        indent = 1,
                        selected = originOn,
                        pulse = if (originOn && !tableOn) pulse else 0f,
                        accent = accent,
                        modifier = Modifier.trackAnchor(anchors) { anchors.cookiesOrigin = it },
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(CookieCopyChrome.ToolsLine))
            CookieCopyTable(
                selected = tableOn,
                accent = accent,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchor(anchors) { anchors.table = it },
            )
        }
    }
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
        modifier.padding(top = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 8.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .height(2.dp)
                .fillMaxWidth()
                .background(if (selected) accent.copy(alpha = 0.55f + 0.45f * pulse) else Color.Transparent),
        )
    }
}

@Composable
private fun TreeLine(
    label: String,
    modifier: Modifier = Modifier,
    indent: Int = 0,
    selected: Boolean = false,
    accented: Boolean = false,
    pulse: Float = 0f,
    accent: Color = DesignColors.Accent,
) {
    val wash = when {
        selected -> accent.copy(alpha = 0.22f + 0.12f * pulse)
        accented -> accent.copy(alpha = 0.12f + 0.10f * pulse)
        else -> Color.Transparent
    }
    Text(
        label,
        style = TextStyle(
            fontSize = 8.sp,
            fontWeight = if (selected || accented) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected || accented) accent else CookieCopyChrome.Text,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(wash)
            .padding(start = (4 + indent * 8).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    )
}

private val DemoCookies = listOf(
    Triple("VISITOR_INFO1_LIVE", "•••", ".youtube.com"),
    Triple("SID", "•••", ".youtube.com"),
    Triple("HSID", "•••", ".google.com"),
    Triple("LOGIN_INFO", "•••", ".youtube.com"),
    Triple("PREF", "•••", ".youtube.com"),
)

@Composable
private fun CookieCopyTable(
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(if (selected) accent.copy(alpha = 0.10f) else CookieCopyChrome.Surface)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, accent.copy(alpha = 0.7f))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        CookieRow("Name", "Value", "Domain", header = true)
        DemoCookies.forEachIndexed { i, row ->
            CookieRow(
                name = row.first,
                value = row.second,
                domain = row.third,
                striped = i % 2 == 1,
                selected = selected,
            )
        }
    }
}

@Composable
private fun CookieRow(
    name: String,
    value: String,
    domain: String,
    header: Boolean = false,
    striped: Boolean = false,
    selected: Boolean = false,
) {
    val style = TextStyle(
        fontSize = 7.sp,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        color = if (header) CookieCopyChrome.TextMuted else CookieCopyChrome.Text,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected && !header -> DesignColors.Accent.copy(alpha = 0.16f)
                    striped -> CookieCopyChrome.Row
                    else -> Color.Transparent
                },
            )
            .padding(vertical = 1.dp),
    ) {
        Text(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
        Text(value, style = style, maxLines = 1, modifier = Modifier.weight(0.5f))
        Text(domain, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
