package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.sound_bass
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_eq_band
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_pitch
import io.ymusic.ui.resources.sound_playback
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
import io.ymusic.ui.resources.sound_speed
import io.ymusic.ui.resources.sound_virtualizer
import io.ymusic.ui.resources.sound_volume
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.round

private val SPEED_CHIPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val PITCH_CHIPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val EQ_HZ = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

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

@Composable
internal fun PlaybackFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    FxDialog(title = stringResource(Res.string.sound_playback), onDismiss = onDismiss) {
        Text(stringResource(Res.string.sound_volume, (config.volume * 100).toInt()))
        Slider(
            value = config.volume,
            onValueChange = { player.setVolume(it) },
            valueRange = 0f..1f,
        )
        Spacer(Modifier.height(8.dp))
        TickRow(
            title = stringResource(Res.string.sound_speed),
            value = config.speed,
            display = "${trimFloat(config.speed)}×",
            range = 0.25f..2f,
            chips = SPEED_CHIPS,
            onChange = { player.setSpeed(it) },
        )
        Spacer(Modifier.height(12.dp))
        TickRow(
            title = stringResource(Res.string.sound_pitch, trimFloat(config.pitchFactor)),
            value = config.pitchFactor,
            display = "${trimFloat(config.pitchFactor)}×",
            range = 0.5f..2f,
            chips = PITCH_CHIPS,
            onChange = { player.setPitch(it) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    player.setVolume(1f)
                    player.setSpeed(1f)
                    player.setPitch(1f)
                },
            ) { Text(stringResource(Res.string.sound_reset)) }
        }
    }
}

@Composable
internal fun EqualizerFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    FxDialog(title = stringResource(Res.string.sound_eq), onDismiss = onDismiss) {
        EqPresetDropdown(
            bands = config.eqBands,
            onSelect = { player.setEqBands(it) },
        )
        Spacer(Modifier.height(8.dp))
        EQ_HZ.forEachIndexed { i, label ->
            val db = config.eqBands.getOrElse(i) { 0f }
            Text(stringResource(Res.string.sound_eq_band, label, db.toInt()))
            Slider(
                value = db,
                onValueChange = { v ->
                    val next = config.eqBands.toMutableList()
                    while (next.size < 10) next.add(0f)
                    next[i] = snapBand(v)
                    player.setEqBands(next)
                },
                valueRange = -12f..12f,
            )
        }
        Text(stringResource(Res.string.sound_bass, config.bassBoostDb.toInt()))
        Slider(
            value = config.bassBoostDb,
            onValueChange = { player.setBassBoost(it) },
            valueRange = 0f..12f,
        )
        Text(stringResource(Res.string.sound_virtualizer, trimFloat(config.virtualizer)))
        Slider(
            value = config.virtualizer,
            onValueChange = { player.setVirtualizer(it) },
            valueRange = 0f..2f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    player.setEqBands(List(10) { 0f })
                    player.setBassBoost(0f)
                    player.setVirtualizer(1f)
                },
            ) { Text(stringResource(Res.string.sound_reset)) }
        }
    }
}

@Composable
private fun FxDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints {
        val elevated = desktopLayoutHole || maxWidth >= 600.dp || maxWidth > maxHeight
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .then(if (elevated) Modifier.widthIn(max = 440.dp) else Modifier.fillMaxWidth())
                    .heightIn(max = maxHeight * 0.9f),
                shape = RoundedCornerShape(if (elevated) 12.dp else 16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = if (elevated) 6.dp else 1.dp,
                shadowElevation = if (elevated) 8.dp else 2.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * 0.9f)
                        .padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sound_ok)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TickRow(
    title: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    chips: List<Float>,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Text(display, color = MaterialTheme.colorScheme.primary)
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { s ->
            FilterChip(
                selected = abs(value - s) < 0.02f,
                onClick = { onChange(s) },
                label = { Text(if (s == 1f) "1×" else "${trimFloat(s)}×") },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onChange(snap05((value - 0.05f).coerceIn(range.start, range.endInclusive))) },
        ) { Text("−") }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { onChange(snap05(it.coerceIn(range.start, range.endInclusive))) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onChange(snap05((value + 0.05f).coerceIn(range.start, range.endInclusive))) },
        ) { Text("+") }
    }
}

@Composable
private fun EqPresetDropdown(bands: List<Float>, onSelect: (List<Float>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val match = EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }
    val label = stringResource(match?.label ?: Res.string.sound_preset_custom)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EQ_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.label)) },
                    onClick = {
                        onSelect(preset.bands)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun snap05(v: Float): Float = round(v * 20f) / 20f

private fun snapBand(v: Float): Float = round(v)

private fun trimFloat(v: Float): String {
    val x = round(v * 100f) / 100f
    val asInt = x.toInt()
    return if (abs(x - asInt) < 0.001f) asInt.toString() else x.toString()
}

private fun bandsMatch(a: List<Float>, b: List<Float>): Boolean {
    if (a.size != b.size) return false
    return a.indices.all { abs(a[it] - b[it]) < 0.2f }
}
