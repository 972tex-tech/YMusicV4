package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.sound_bass
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_eq_hint
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_preset_bass
import io.ymusic.ui.resources.sound_preset_classical
import io.ymusic.ui.resources.sound_preset_custom
import io.ymusic.ui.resources.sound_preset_electronic
import io.ymusic.ui.resources.sound_preset_flat
import io.ymusic.ui.resources.sound_preset_jazz
import io.ymusic.ui.resources.sound_preset_pop
import io.ymusic.ui.resources.sound_preset_rock
import io.ymusic.ui.resources.sound_preset_treble
import io.ymusic.ui.resources.sound_preset_voice
import io.ymusic.ui.resources.sound_reset
import io.ymusic.ui.resources.sound_virtualizer_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val EQ_HZ = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private const val EQ_MAX_DB = 12f
private const val BASS_MAX_DB = 12f
private const val VIRTUALIZER_MAX = 2f

private val FaderBankCorner = 14.dp
private val FaderHeight = 180.dp
private val FaderTrackWidth = 4.dp
private val FaderCapWidth = 22.dp
private val FaderCapHeight = 14.dp
private val KnobSize = 104.dp
private val KnobStroke = 7.dp
private val MenuCurveWidth = 26.dp
private val MenuCurveHeight = 12.dp

private data class EqPreset(val label: StringResource, val bands: List<Float>)

private val EQ_PRESETS = listOf(
    EqPreset(Res.string.sound_preset_flat, List(10) { 0f }),
    EqPreset(Res.string.sound_preset_bass, listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset(Res.string.sound_preset_treble, listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f)),
    EqPreset(Res.string.sound_preset_voice, listOf(-4f, -3f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    EqPreset(Res.string.sound_preset_jazz, listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
    EqPreset(Res.string.sound_preset_rock, listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    EqPreset(Res.string.sound_preset_pop, listOf(-1f, 2f, 4f, 4f, 1f, -1f, -2f, -2f, 1f, 2f)),
    EqPreset(Res.string.sound_preset_classical, listOf(5f, 4f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqPreset(Res.string.sound_preset_electronic, listOf(4f, 3f, 1f, 0f, -2f, 2f, 1f, 2f, 3f, 4f)),
)

/** Equalizer: a mixing-desk bank of ten faders, a preset menu in the title bar, and bass / virtualizer knobs. */
@Composable
internal fun EqualizerFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    val bands = List(EQ_HZ.size) { config.eqBands.getOrElse(it) { 0f } }
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.sound_eq),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        titleAction = { PresetMenu(bands, onSelect = { player.setEqBands(it) }) },
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                player.setEqBands(List(EQ_HZ.size) { 0f })
                player.setBassBoost(0f)
                player.setVirtualizer(1f)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            Column(Modifier.padding(horizontal = DesignSpacing.Space16)) {
                FaderBank(bands, onBandChange = { index, db -> player.setEqBands(bands.toMutableList().also { it[index] = db }) })
                Text(
                    stringResource(Res.string.sound_eq_hint),
                    style = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = DesignSpacing.Space8),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RotaryKnob(
                    title = stringResource(Res.string.sound_bass),
                    fraction = (config.bassBoostDb + BASS_MAX_DB) / (2 * BASS_MAX_DB),
                    valueText = "${dbLabel(config.bassBoostDb)} dB",
                    onChange = { player.setBassBoost((it * 2 * BASS_MAX_DB - BASS_MAX_DB).roundToInt().toFloat()) },
                )
                RotaryKnob(
                    title = stringResource(Res.string.sound_virtualizer_title),
                    fraction = config.virtualizer / VIRTUALIZER_MAX,
                    valueText = "${trimFloat(config.virtualizer)}×",
                    onChange = { player.setVirtualizer((it * VIRTUALIZER_MAX * 10).roundToInt() / 10f) },
                )
            }
        }
    }
}

/** The current preset's name ("Custom" when the bands match none); opens the preset list, each with its curve. */
@Composable
private fun PresetMenu(bands: List<Float>, onSelect: (List<Float>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val match = EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(DesignColors.SettingsCard)
                .border(1.dp, DesignColors.OutlineSoft, shape)
                .clickable { open = true }
                .padding(horizontal = DesignSpacing.Space8 + DesignSpacing.Space2, vertical = DesignSpacing.Space4 + DesignSpacing.Space2),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4 + DesignSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(match?.label ?: Res.string.sound_preset_custom),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            val chevron = DesignColors.InkSecondary
            Canvas(Modifier.size(10.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.35f)
                    lineTo(size.width * 0.5f, size.height * 0.65f)
                    lineTo(size.width * 0.8f, size.height * 0.35f)
                }
                drawPath(path, chevron, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EQ_PRESETS.forEach { preset ->
                val selected = preset == match
                DropdownMenuItem(
                    leadingIcon = { MiniCurve(preset.bands, if (selected) DesignColors.Accent else DesignColors.InkSecondary) },
                    text = {
                        Text(
                            stringResource(preset.label),
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            ),
                        )
                    },
                    onClick = {
                        onSelect(preset.bands)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MiniCurve(bands: List<Float>, color: Color) {
    Canvas(Modifier.size(MenuCurveWidth, MenuCurveHeight)) {
        val points = bands.indices.map { i ->
            Offset(size.width * i / (bands.size - 1), size.height / 2 - bands[i] / EQ_MAX_DB * size.height / 2)
        }
        drawPath(smoothPath(points), color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Ten mixing-desk faders over a ±12 dB scale, each filled from the 0 dB line to its value. Press anywhere in a
 * fader's column and drag vertically to set it (whole-dB steps); the held fader's cap glows and its value and
 * frequency light up.
 */
@Composable
private fun FaderBank(bands: List<Float>, onBandChange: (index: Int, db: Float) -> Unit) {
    val accent = DesignColors.Accent
    val track = if (DesignColors.isDark) Color(0xFF1A1A1A) else Color(0xFFD4D4D4)
    val rule = DesignColors.OutlineSoft
    val idleMark = DesignColors.InkFaint
    val change by rememberUpdatedState(onBandChange)
    var active by remember { mutableStateOf<Int?>(null) }
    val inset = with(LocalDensity.current) { FaderCapHeight.toPx() / 2 + 2.dp.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FaderBankCorner))
            .background(Brush.verticalGradient(listOf(DesignColors.SettingsCard, DesignColors.Surface)))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(FaderBankCorner))
            .padding(horizontal = DesignSpacing.Space4, vertical = DesignSpacing.Space16),
    ) {
        Row(Modifier.fillMaxWidth()) {
            bands.forEachIndexed { i, db ->
                Text(
                    dbLabel(db),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (db == 0f && i != active) DesignColors.InkSecondary else accent,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(FaderHeight).padding(top = DesignSpacing.Space8)) {
            val height = constraints.maxHeight.toFloat()
            fun yOf(db: Float) = inset + (EQ_MAX_DB - db) / (2 * EQ_MAX_DB) * (height - 2 * inset)
            fun dbAt(y: Float) = ((1f - (y - inset) / (height - 2 * inset)) * 2 * EQ_MAX_DB - EQ_MAX_DB)
                .coerceIn(-EQ_MAX_DB, EQ_MAX_DB).roundToInt().toFloat()
            Canvas(
                Modifier.fillMaxSize().pointerInput(bands.size, height) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val index = (down.position.x / size.width * bands.size).toInt().coerceIn(0, bands.lastIndex)
                        active = index
                        down.consume()
                        change(index, dbAt(down.position.y))
                        drag(down.id) { move ->
                            change(index, dbAt(move.position.y))
                            move.consume()
                        }
                        active = null
                    }
                },
            ) {
                val column = size.width / bands.size
                val zero = yOf(0f)
                drawLine(rule, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.5.dp.toPx())
                listOf(EQ_MAX_DB, -EQ_MAX_DB).forEach { db ->
                    drawLine(rule.copy(alpha = 0.5f), Offset(0f, yOf(db)), Offset(size.width, yOf(db)), strokeWidth = 1.dp.toPx())
                }
                val trackWidth = FaderTrackWidth.toPx()
                val capWidth = FaderCapWidth.toPx()
                val capHeight = FaderCapHeight.toPx()
                bands.forEachIndexed { i, db ->
                    val x = column * (i + 0.5f)
                    val radius = CornerRadius(trackWidth / 2)
                    drawRoundRect(track, Offset(x - trackWidth / 2, inset), Size(trackWidth, size.height - 2 * inset), radius)
                    val y = yOf(db)
                    if (db != 0f) {
                        drawRoundRect(accent, Offset(x - trackWidth / 2, minOf(y, zero)), Size(trackWidth, abs(y - zero)), radius)
                    }
                    val capTopLeft = Offset(x - capWidth / 2, y - capHeight / 2)
                    if (i == active) drawCircle(accent.copy(alpha = 0.25f), capWidth * 0.9f, Offset(x, y))
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.45f),
                        capTopLeft + Offset(0f, 2.dp.toPx()),
                        Size(capWidth, capHeight),
                        CornerRadius(4.dp.toPx()),
                    )
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFFEDEDED), Color(0xFFA9A9A9)), startY = capTopLeft.y, endY = capTopLeft.y + capHeight),
                        capTopLeft,
                        Size(capWidth, capHeight),
                        CornerRadius(4.dp.toPx()),
                    )
                    drawLine(
                        if (db == 0f && i != active) idleMark else accent,
                        Offset(x - capWidth / 2 + 4.dp.toPx(), y),
                        Offset(x + capWidth / 2 - 4.dp.toPx(), y),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = DesignSpacing.Space8)) {
            EQ_HZ.forEachIndexed { i, hz ->
                Text(
                    hz,
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == active) accent else DesignColors.InkSecondary,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun dbLabel(db: Float): String {
    val value = db.roundToInt()
    return when {
        value > 0 -> "+$value"
        value < 0 -> "−${-value}"
        else -> "0"
    }
}

/**
 * A 270° rotary control: drag up or right to raise, down or left to lower. The value arc fills from the
 * lower-left stop; the face's dot points at the current value.
 */
@Composable
private fun RotaryKnob(title: String, fraction: Float, valueText: String, onChange: (Float) -> Unit) {
    val accent = DesignColors.Accent
    val track = DesignColors.OutlineSoft
    val faceLight = DesignColors.SurfaceElevated
    val faceDark = DesignColors.SettingsCard
    val current by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(KnobSize).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    var value = current
                    drag(down.id) { move ->
                        val delta = move.positionChange()
                        value = (value + (delta.x - delta.y) / (size.height * 1.5f)).coerceIn(0f, 1f)
                        change(value)
                        move.consume()
                    }
                    dragging = false
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = KnobStroke.toPx()
                val pad = stroke / 2 + 2.dp.toPx()
                val arcTopLeft = Offset(pad, pad)
                val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
                drawArc(track, 135f, 270f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = 270f * current
                if (sweep > 0f) {
                    drawArc(
                        accent.copy(alpha = if (dragging) 0.35f else 0.2f),
                        135f,
                        sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(stroke * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(accent, 135f, sweep, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val faceRadius = size.minDimension / 2 - pad - stroke - 6.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(faceLight, faceDark), center = center, radius = faceRadius), faceRadius)
                drawCircle(track, faceRadius, style = Stroke(1.dp.toPx()))
                val angle = (135f + sweep) * PI.toFloat() / 180f
                val dotDistance = faceRadius - 9.dp.toPx()
                drawCircle(accent, 3.5.dp.toPx(), center + Offset(cos(angle) * dotDistance, sin(angle) * dotDistance))
            }
            Text(valueText, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        }
        Text(
            title,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary),
            modifier = Modifier.padding(top = DesignSpacing.Space4),
        )
    }
}

/** A Catmull-Rom spline through [points], as cubic Béziers. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 0 until points.lastIndex) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
}

private fun bandsMatch(a: List<Float>, b: List<Float>): Boolean =
    a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.2f }
