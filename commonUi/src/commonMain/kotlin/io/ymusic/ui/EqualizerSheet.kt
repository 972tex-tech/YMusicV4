package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.sound_bass_boost
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

private val GraphHeight = 200.dp
private val GraphCorner = 16.dp
private val NodeRadius = 6.dp
private val ActiveNodeRadius = 9.dp
private val CurveStroke = 3.dp
private val KnobSize = 104.dp
private val KnobStroke = 7.dp
private val ChipCurveWidth = 26.dp
private val ChipCurveHeight = 12.dp

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

/** Equalizer: a draggable response curve over the ten bands, preset chips, and bass / virtualizer knobs. */
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
            PresetChips(bands, onSelect = { player.setEqBands(it) })
            Column(Modifier.padding(horizontal = DesignSpacing.Space24)) {
                EqGraph(bands, onBandChange = { index, db -> player.setEqBands(bands.toMutableList().also { it[index] = db }) })
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
                    title = stringResource(Res.string.sound_bass_boost),
                    fraction = config.bassBoostDb / BASS_MAX_DB,
                    valueText = "+${config.bassBoostDb.roundToInt()} dB",
                    onChange = { player.setBassBoost((it * BASS_MAX_DB).roundToInt().toFloat()) },
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

@Composable
private fun PresetChips(bands: List<Float>, onSelect: (List<Float>) -> Unit) {
    val match = EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = DesignSpacing.Space24),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        if (match == null) PresetChip(stringResource(Res.string.sound_preset_custom), bands, selected = true, onClick = null)
        EQ_PRESETS.forEach { preset ->
            PresetChip(stringResource(preset.label), preset.bands, selected = preset == match) { onSelect(preset.bands) }
        }
    }
}

@Composable
private fun PresetChip(label: String, bands: List<Float>, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(999.dp)
    val ink = if (selected) Color.White else DesignColors.Ink
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) DesignColors.Accent else DesignColors.SettingsCard)
            .border(1.dp, if (selected) DesignColors.Accent else DesignColors.OutlineSoft, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val curveColor = if (selected) Color.White else DesignColors.Accent
        Canvas(Modifier.size(ChipCurveWidth, ChipCurveHeight)) {
            val points = bands.indices.map { i ->
                Offset(size.width * i / (bands.size - 1), size.height / 2 - bands[i] / EQ_MAX_DB * size.height / 2)
            }
            drawPath(smoothPath(points), curveColor, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink))
    }
}

/**
 * The ten bands as a smooth response curve over a ±12 dB grid. Press anywhere in a band's column and drag
 * vertically to set it (whole-dB steps); the band's node grows and a dB bubble follows the finger.
 */
@Composable
private fun EqGraph(bands: List<Float>, onBandChange: (index: Int, db: Float) -> Unit) {
    val accent = DesignColors.Accent
    val grid = DesignColors.OutlineSoft
    val nodeFill = DesignColors.Surface
    val change by rememberUpdatedState(onBandChange)
    var active by remember { mutableStateOf<Int?>(null) }
    val inset = with(LocalDensity.current) { (ActiveNodeRadius + DesignSpacing.Space8).toPx() }
    Column {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(GraphHeight)
                .clip(RoundedCornerShape(GraphCorner))
                .background(DesignColors.SettingsCard)
                .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(GraphCorner)),
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            fun xOf(index: Int) = (index + 0.5f) * width / bands.size
            fun yOf(db: Float) = inset + (EQ_MAX_DB - db) / (2 * EQ_MAX_DB) * (height - 2 * inset)
            fun dbAt(y: Float) = ((1f - (y - inset) / (height - 2 * inset)) * 2 * EQ_MAX_DB - EQ_MAX_DB)
                .coerceIn(-EQ_MAX_DB, EQ_MAX_DB).roundToInt().toFloat()

            Canvas(
                Modifier.fillMaxSize().pointerInput(bands.size, width, height) {
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
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
                    val y = yOf(db)
                    drawLine(
                        if (db == 0f) grid else grid.copy(alpha = 0.6f),
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = (if (db == 0f) 1.5.dp else 1.dp).toPx(),
                        pathEffect = if (db == 0f) null else dash,
                    )
                }
                bands.indices.forEach { i ->
                    drawLine(grid.copy(alpha = 0.35f), Offset(xOf(i), inset), Offset(xOf(i), size.height - inset), 1.dp.toPx())
                }
                val points = bands.indices.map { Offset(xOf(it), yOf(bands[it])) }
                val curve = smoothPath(listOf(Offset(0f, points.first().y)) + points + Offset(size.width, points.last().y))
                val fill = Path().apply {
                    addPath(curve)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))))
                drawPath(curve, accent.copy(alpha = 0.22f), style = Stroke(CurveStroke.toPx() * 3, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(curve, accent, style = Stroke(CurveStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                points.forEachIndexed { i, point ->
                    val radius = (if (i == active) ActiveNodeRadius else NodeRadius).toPx()
                    if (i == active) drawCircle(accent.copy(alpha = 0.2f), radius * 2.2f, point)
                    drawCircle(nodeFill, radius, point)
                    drawCircle(accent, radius, point, style = Stroke(2.5.dp.toPx()))
                }
            }
            ScaleLabel("+12", Modifier.align(Alignment.TopStart))
            ScaleLabel("0", Modifier.align(Alignment.CenterStart))
            ScaleLabel("−12", Modifier.align(Alignment.BottomStart))
            active?.let { index ->
                val gap = with(LocalDensity.current) { (ActiveNodeRadius + DesignSpacing.Space8).toPx() }
                DbBubble(bands[index], anchor = Offset(xOf(index), yOf(bands[index])), gap = gap)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = DesignSpacing.Space8)) {
            EQ_HZ.forEachIndexed { i, hz ->
                Text(
                    hz,
                    style = TextStyle(
                        fontSize = 11.sp,
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

@Composable
private fun ScaleLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = 10.sp, color = DesignColors.InkSecondary),
        modifier = modifier.padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
    )
}

/** "+4 dB" pill above [anchor] (below it when there is no room), kept inside the graph. */
@Composable
private fun DbBubble(db: Float, anchor: Offset, gap: Float) {
    val label = (if (db > 0) "+" else "") + "${db.roundToInt()} dB"
    Text(
        label,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White),
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = (anchor.x - placeable.width / 2f).roundToInt().coerceIn(0, constraints.maxWidth - placeable.width)
                    val above = anchor.y - gap - placeable.height
                    val y = (if (above >= 0) above else anchor.y + gap).roundToInt()
                    placeable.place(x, y)
                }
            }
            .clip(RoundedCornerShape(999.dp))
            .background(DesignColors.Accent)
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
    )
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
