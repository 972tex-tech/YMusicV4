package io.ymusic.desktop.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.ymusic_logo
import org.jetbrains.compose.resources.painterResource

/**
 * The first-run screen: two steps, no way past the first until the app can actually do anything.
 *
 * Step one is a gate on the two engines the app downloads for itself; VLC and the music folder sit
 * below a line because they can genuinely wait. Step two asks the one question owed to the user
 * before anything leaves their machine. See design/onboarding (published mockup) — the copy and the
 * row shapes here are that design.
 */
@Composable
fun OnboardingScreen(
    state: SetupState,
    crashReports: Boolean,
    onCrashReportsChange: (Boolean) -> Unit,
    onRetryEngine: () -> Unit,
    onRetryMediaTools: () -> Unit,
    onRecheckVlc: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onFinish: () -> Unit,
    /** Which step to open on — only ever moved off 1 by the snapshot harness. */
    initialStep: Int = 1,
) {
    var step by remember { mutableStateOf(initialStep) }
    Box(
        Modifier.fillMaxSize().background(DesignColors.Surface).padding(
            horizontal = SetupTheme.ScreenHorizontal,
            vertical = SetupTheme.ScreenVertical,
        ),
    ) {
        if (step == 1) {
            SetupStep(state, onRetryEngine, onRetryMediaTools, onRecheckVlc, onChooseFolder, onOpenUrl) { step = 2 }
        } else {
            ConsentStep(crashReports, onCrashReportsChange, onOpenUrl, onFinish)
        }
    }
}

@Composable
private fun SetupStep(
    state: SetupState,
    onRetryEngine: () -> Unit,
    onRetryMediaTools: () -> Unit,
    onRecheckVlc: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StepHeader(step = 1, title = "Get YMusic ready")
        Spacer(Modifier.height(DesignSpacing.Space24))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            GatedRow(
                name = "YouTube playback",
                detail = "Chromium engine",
                state = state.engine,
                onRetry = onRetryEngine,
            )
            GatedRow(
                name = "Media tools",
                detail = "ffmpeg",
                state = state.mediaTools,
                onRetry = onRetryMediaTools,
            )

            Spacer(Modifier.height(DesignSpacing.Space8))
            Box(Modifier.fillMaxWidth().height(1.dp).background(DesignColors.Divider))
            Text(
                "CAN BE DONE LATER",
                color = DesignColors.InkSecondary,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium,
            )

            VlcRow(state.vlc, onRecheckVlc, onOpenUrl)
            MusicFolderRow(state, onChooseFolder)
        }
        Footer(
            note = gateNote(state),
            buttonLabel = "Next",
            enabled = state.canContinue,
            onClick = onNext,
        )
    }
}

/**
 * The footer's running summary. It totals only the rows that actually report bytes — the Chromium
 * bundle reports a percentage and nothing else, so a combined figure would be part measurement and
 * part invention.
 */
private fun gateNote(state: SetupState): String {
    if (state.canContinue) return "Ready"
    if (state.engine is ComponentState.Failed || state.mediaTools is ComponentState.Failed) {
        return "A download needs another try"
    }
    val counted = listOf(state.engine, state.mediaTools).filterIsInstance<ComponentState.Working>()
        .filter { it.total > 0 }
    if (counted.isEmpty()) return "Downloading…"
    return "Downloading ${megabytes(counted.sumOf { it.done })} of ${megabytes(counted.sumOf { it.total })}"
}

@Composable
private fun ConsentStep(
    crashReports: Boolean,
    onCrashReportsChange: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StepHeader(step = 2, title = "Before you start")
        Spacer(Modifier.height(DesignSpacing.Space24))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            ConsentRow(
                checked = crashReports,
                onCheckedChange = onCrashReportsChange,
                name = "Send anonymous crash reports",
                detail = "Stack traces only — no listening history, no account data, no file names.",
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(DesignColors.Divider))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
                Text("★", fontSize = 15.sp, color = SetupTheme.warn, modifier = Modifier.width(GLYPH_COLUMN))
                Column(Modifier.weight(1f)) {
                    RowName("Star us on GitHub")
                    RowMeta("YMusic is free and open source. A star helps other people find it.")
                    Spacer(Modifier.height(DesignSpacing.Space8))
                    LinkButton("Star on GitHub") { onOpenUrl(GITHUB_URL) }
                }
            }
            Spacer(Modifier.height(DesignSpacing.Space8))
            TermsLine(onOpenUrl)
        }
        Footer(
            note = "Setup is done — Continue opens YMusic",
            buttonLabel = "Continue",
            enabled = true,
            onClick = onFinish,
        )
    }
}

@Composable
private fun StepHeader(step: Int, title: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        Image(
            painter = painterResource(Res.drawable.ymusic_logo),
            contentDescription = null,
            modifier = Modifier.size(SetupTheme.MarkSize).clip(CircleShape),
        )
        Column {
            Text(
                "STEP $step OF 2",
                color = SetupTheme.accent,
                fontSize = 11.sp,
                letterSpacing = 1.3.sp,
                fontWeight = FontWeight.SemiBold,
                // The label sets its own line height: its default leading pushed it off the
                // logo's optical top, which is what the header is aligned to.
                lineHeight = 11.sp,
            )
            Spacer(Modifier.height(SetupTheme.StepLabelGap))
            Text(title, color = DesignColors.InkStrong, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One of the two components the app installs itself: glyph, name, byte counter, progress ring. */
@Composable
private fun GatedRow(name: String, detail: String, state: ComponentState, onRetry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        StatusGlyph(state)
        Column(Modifier.weight(1f)) {
            RowName(name)
            RowMeta(detail + stateSuffix(state))
            if (state is ComponentState.Failed) {
                // Only two things appear on failure — the reason and a retry. Everything else in
                // the row holds its shape, so nothing moves under the pointer.
                Spacer(Modifier.height(DesignSpacing.Space2))
                Text(state.reason, color = SetupTheme.bad, fontSize = 12.sp)
                Spacer(Modifier.height(DesignSpacing.Space8))
                LinkButton("Try again", strong = true, onClick = onRetry)
            }
        }
        ProgressRing(state)
    }
}

/** " · 12 MB of 34 MB" while a size is known, " · installed" once it is done, nothing otherwise —
 *  a row never shows a byte count it had to guess at. */
private fun stateSuffix(state: ComponentState): String = when {
    state is ComponentState.Ready -> " · installed"
    state is ComponentState.Working && state.total > 0 ->
        " · ${megabytes(state.done)} of ${megabytes(state.total)}"
    else -> ""
}

private fun megabytes(bytes: Long): String = "${bytes / 1024 / 1024} MB"

@Composable
private fun VlcRow(state: ComponentState, onRecheck: () -> Unit, onOpenUrl: (String) -> Unit) {
    val hint = remember { VlcProbe.installHint() }
    val clipboard = LocalClipboardManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        StatusGlyph(state, absentGlyph = "▲", absentColor = SetupTheme.warn)
        Column(Modifier.weight(1f)) {
            RowName("Local file playback")
            if (state is ComponentState.Ready) {
                RowMeta("VLC found — your own files will play")
            } else {
                RowMeta("Needs VLC — we can't install it for you")
                Spacer(Modifier.height(SetupTheme.RowInnerGap))
                if (hint.preferSite) {
                    // Windows: almost nobody has used winget, so the download page leads and the
                    // command is the alternative underneath.
                    LinkButton("Download from videolan.org", strong = true) { onOpenUrl(hint.site) }
                    Spacer(Modifier.height(SetupTheme.RowInnerGap))
                    RowMeta("or, with the Windows Package Manager:")
                    Spacer(Modifier.height(DesignSpacing.Space4))
                    hint.command?.let { CommandChip(it) { clipboard.setText(AnnotatedString(it)) } }
                } else {
                    hint.command?.let { CommandChip(it) { clipboard.setText(AnnotatedString(it)) } }
                    Spacer(Modifier.height(DesignSpacing.Space4))
                    LinkText("or download from videolan.org") { onOpenUrl(hint.site) }
                }
                Spacer(Modifier.height(DesignSpacing.Space8))
                LinkButton("Recheck", onClick = onRecheck)
            }
        }
    }
}

@Composable
private fun MusicFolderRow(state: SetupState, onChoose: () -> Unit) {
    val waiting = !state.canChooseFolder && state.musicFolder == null
    Row(
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        modifier = Modifier.alpha(if (waiting) SetupTheme.WaitingAlpha else 1f),
    ) {
        Text("○", color = DesignColors.InkSecondary, fontSize = 13.sp, modifier = Modifier.width(GLYPH_COLUMN))
        Column(Modifier.weight(1f)) {
            RowName("Your music folder")
            // Once a folder is set it replaces the explanation: the sentence has done its job.
            if (state.musicFolder != null) {
                Text(state.musicFolder, color = DesignColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            } else {
                RowMeta("The folder is scanned into your library")
            }
            Spacer(Modifier.height(DesignSpacing.Space8))
            LinkButton(
                label = if (state.musicFolder != null) "Change…" else "Choose…",
                enabled = state.canChooseFolder,
                onClick = onChoose,
            )
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, name: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(SetupTheme.CheckboxSize)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) SetupTheme.accent else Color.Transparent)
                .border(1.dp, if (checked) SetupTheme.accent else DesignColors.Outline, RoundedCornerShape(4.dp))
                .clickableRow { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 11.sp)
        }
        Column(Modifier.weight(1f).clickableRow { onCheckedChange(!checked) }) {
            RowName(name)
            RowMeta(detail)
        }
    }
}

@Composable
private fun TermsLine(onOpenUrl: (String) -> Unit) {
    // No checkbox: continuing IS the acceptance, and both documents are one click away right here.
    // Built as one annotated sentence so it wraps like prose rather than breaking into a row of
    // fragments the eye has to reassemble.
    val text = buildAnnotatedString {
        append("By continuing you accept the ")
        appendLink("Terms of Use", TERMS_URL, onOpenUrl)
        append(" and ")
        appendLink("Privacy Policy", PRIVACY_URL, onOpenUrl)
        append(".")
    }
    Text(text, color = DesignColors.InkSecondary, fontSize = 12.sp, lineHeight = 18.sp)
}

@Composable
private fun AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    onOpenUrl: (String) -> Unit,
) {
    val style = SpanStyle(color = SetupTheme.accent, textDecoration = TextDecoration.Underline)
    withLink(
        LinkAnnotation.Clickable(tag = url, styles = TextLinkStyles(style = style)) { onOpenUrl(url) },
    ) { append(label) }
}

@Composable
private fun Footer(note: String, buttonLabel: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = DesignSpacing.Space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(note, color = DesignColors.InkSecondary, fontSize = 12.sp)
        PrimaryButton(buttonLabel, enabled, onClick)
    }
}

@Composable
private fun StatusGlyph(
    state: ComponentState,
    absentGlyph: String = "○",
    absentColor: Color = DesignColors.InkSecondary,
) {
    val (glyph, color) = when (state) {
        is ComponentState.Ready -> "✓" to SetupTheme.ok
        is ComponentState.Failed -> "✕" to SetupTheme.bad
        is ComponentState.Working -> "◐" to SetupTheme.progress
        is ComponentState.Absent -> absentGlyph to absentColor
        ComponentState.Pending -> "○" to DesignColors.InkSecondary
    }
    Text(glyph, color = color, fontSize = 13.sp, modifier = Modifier.width(GLYPH_COLUMN))
}

/**
 * The 46dp ring: orange while it fills, a green tick when it closes, a red mark when it fails.
 * It never disappears — the row keeps its place, so nothing shifts under the pointer at the moment
 * someone is about to click.
 */
@Composable
private fun ProgressRing(state: ComponentState) {
    if (state is ComponentState.Pending || state is ComponentState.Absent) {
        Spacer(Modifier.size(SetupTheme.RingSize))
        return
    }
    val target = when (state) {
        is ComponentState.Working -> state.fraction ?: 0f
        else -> 1f
    }
    val fraction by animateFloatAsState(target, label = "setup-ring")
    val track = DesignColors.Divider
    val arc = when (state) {
        is ComponentState.Ready -> SetupTheme.ok
        is ComponentState.Failed -> SetupTheme.bad
        else -> SetupTheme.progress
    }
    Box(Modifier.size(SetupTheme.RingSize), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = SetupTheme.RingStroke.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = arc,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
        when (state) {
            is ComponentState.Ready -> Text("✓", color = SetupTheme.ok, fontSize = 13.sp)
            is ComponentState.Failed -> Text("!", color = SetupTheme.bad, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            else -> Text(
                "${(fraction * 100).toInt()}%",
                color = DesignColors.InkMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RowName(text: String) =
    Text(text, color = DesignColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)

@Composable
private fun RowMeta(text: String) =
    Text(text, color = DesignColors.InkSecondary, fontSize = 12.sp)

@Composable
private fun CommandChip(command: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
        Text(
            command,
            color = DesignColors.InkMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(DesignColors.SurfaceElevated)
                .border(1.dp, DesignColors.Divider, RoundedCornerShape(5.dp))
                .padding(horizontal = DesignSpacing.Space8, vertical = 3.dp),
        )
        LinkButton("Copy", tiny = true, onClick = onCopy)
    }
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) =
    Text(
        label,
        color = SetupTheme.accent,
        fontSize = 12.sp,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickableRow(onClick),
    )

@Composable
private fun LinkButton(
    label: String,
    strong: Boolean = false,
    tiny: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content = if (enabled) SetupTheme.accent else DesignColors.InkSecondary
    Text(
        label,
        color = content,
        fontSize = if (tiny) 11.sp else 12.sp,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) DesignColors.Outline else DesignColors.Divider, RoundedCornerShape(6.dp))
            .let { if (enabled) it.clickableRow(onClick) else it }
            .padding(
                horizontal = if (tiny) DesignSpacing.Space8 else 10.dp,
                vertical = if (tiny) 2.dp else DesignSpacing.Space4,
            ),
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else DesignColors.InkSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) SetupTheme.accent else DesignColors.Divider)
            .let { if (enabled) it.clickableRow(onClick) else it }
            .padding(horizontal = 22.dp, vertical = 9.dp),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

private val GLYPH_COLUMN = 20.dp

private const val GITHUB_URL = "https://github.com/khang-nt/ymusic"
private const val TERMS_URL = "https://github.com/khang-nt/ymusic/blob/main/docs/terms.md"
private const val PRIVACY_URL = "https://github.com/khang-nt/ymusic/blob/main/docs/privacy.md"

/**
 * The first run's own palette and geometry.
 *
 * Progress gets its own colour scale here, separate from the brand: a ring tinted with the app's
 * accent could not also say "done" without the two meanings colliding, so state (orange working,
 * green done, red failed) and identity (the green accent) are deliberately different hues. The
 * greens are the logo's, darkened for contrast on a white surface.
 */
private object SetupTheme {
    val accent: Color @Composable get() = if (DesignColors.isDark) Color(0xFF35A96A) else Color(0xFF1F6B44)
    val progress: Color @Composable get() = if (DesignColors.isDark) Color(0xFFF08A45) else Color(0xFFE8732A)
    val ok: Color @Composable get() = if (DesignColors.isDark) Color(0xFF4FD48C) else Color(0xFF3FC37C)
    val bad: Color @Composable get() = if (DesignColors.isDark) Color(0xFFE0736A) else Color(0xFFB4453A)
    val warn: Color @Composable get() = if (DesignColors.isDark) Color(0xFFD9A441) else Color(0xFFB4791C)

    /** Off the spacing scale: the card's own inset, tuned to the 520dp first-run window. */
    val ScreenHorizontal = 28.dp
    val ScreenVertical = 26.dp
    val MarkSize = 40.dp
    val RingSize = 46.dp
    val RingStroke = 3.dp
    val CheckboxSize = 16.dp

    /** Between the step label and the title — the label's cap height, so they read as one block. */
    val StepLabelGap = 7.dp

    /** Inside a row, between its text and the controls beneath it. */
    val RowInnerGap = 7.dp

    /** A row that cannot act yet still has to be readable, so it dims rather than greys out. */
    const val WaitingAlpha = 0.65f
}
