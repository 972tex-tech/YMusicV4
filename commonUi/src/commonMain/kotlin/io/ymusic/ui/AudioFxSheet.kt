package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.sound_bass
import io.ymusic.ui.resources.sound_eq
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

/** Same chips for speed and pitch. */
private val RATE_CHIPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
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

private val DialogPad = Modifier.padding(horizontal = 24.dp)

@Composable
internal fun PlaybackFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.sound_playback),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                player.volume = 1f
                player.speed = 1f
                player.setPitch(1f)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(Modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
            LabelValue(
                stringResource(Res.string.sound_volume, (config.volume * 100).toInt()),
                "",
            )
            FxBar(
                fraction = config.volume,
                onChange = { player.volume = it },
            )
            Spacer(Modifier.height(16.dp))
            RateBlock(
                title = stringResource(Res.string.sound_speed),
                value = config.speed,
                range = 0.25f..3f,
                onChange = { player.speed = it },
            )
            Spacer(Modifier.height(20.dp))
            RateBlock(
                title = stringResource(Res.string.sound_pitch, "").trim(),
                value = config.pitchFactor,
                range = 0.5f..2f,
                onChange = { player.setPitch(it) },
            )
        }
    }
}

@Composable
internal fun EqualizerFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    val scroll = rememberScrollState()
    ElevatedBarDialog(
        title = stringResource(Res.string.sound_eq),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                player.setEqBands(List(10) { 0f })
                player.setBassBoost(0f)
                player.setVirtualizer(1f)
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(Modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
            EqPresetDropdown(
                bands = config.eqBands,
                onSelect = { player.setEqBands(it) },
            )
            Spacer(Modifier.height(8.dp))
            EQ_HZ.forEachIndexed { i, hz ->
                val db = config.eqBands.getOrElse(i) { 0f }
                EqBandRow(
                    label = hz,
                    db = db,
                    onChange = { v ->
                        val next = config.eqBands.toMutableList()
                        while (next.size < 10) next.add(0f)
                        next[i] = v
                        player.setEqBands(next)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            LabelValue(stringResource(Res.string.sound_bass, config.bassBoostDb.toInt()), "")
            FxBar(
                fraction = config.bassBoostDb / 12f,
                onChange = { player.setBassBoost(it * 12f) },
            )
            Spacer(Modifier.height(12.dp))
            LabelValue(stringResource(Res.string.sound_virtualizer, trimFloat(config.virtualizer)), "")
            FxBar(
                fraction = config.virtualizer / 2f,
                onChange = { player.setVirtualizer(it * 2f) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateBlock(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    LabelValue("$title ×${trimFloat(value)}", "")
    FlowRow(
        DialogPad.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RATE_CHIPS.forEach { s ->
            RateChip(
                label = if (s == 1f) "1×" else "${trimFloat(s)}×",
                selected = abs(value - s) < 0.02f,
                onClick = { onChange(s) },
            )
        }
    }
    Row(
        DialogPad.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stepper("−") { onChange(snap05((value - 0.05f).coerceIn(range.start, range.endInclusive))) }
        FxBar(
            fraction = (value - range.start) / (range.endInclusive - range.start),
            onChange = { f ->
                onChange(snap05(range.start + f * (range.endInclusive - range.start)))
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Stepper("+") { onChange(snap05((value + 0.05f).coerceIn(range.start, range.endInclusive))) }
    }
}

@Composable
private fun EqBandRow(label: String, db: Float, onChange: (Float) -> Unit) {
    Row(
        DialogPad.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            modifier = Modifier.width(36.dp),
        )
        FxBar(
            fraction = (db + 12f) / 24f,
            onChange = { onChange(round(it * 24f - 12f)) },
            modifier = Modifier.weight(1f),
        )
        Text(
            if (db > 0.4f) "+${db.toInt()}" else db.toInt().toString(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun FxBar(fraction: Float, onChange: (Float) -> Unit, modifier: Modifier = DialogPad.fillMaxWidth()) {
    SlimSeekBar(
        fraction = fraction.coerceIn(0f, 1f),
        enabled = true,
        onSeek = onChange,
        onScrub = { f -> if (f != null) onChange(f) },
        trackColor = DesignColors.OutlineSoft,
        modifier = modifier,
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        DialogPad.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink))
        if (value.isNotEmpty()) {
            Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent))
        }
    }
}

@Composable
private fun RateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = TextStyle(
            fontSize = 12.sp,
            color = if (selected) Color.White else DesignColors.InkSoft,
        ),
        modifier = Modifier
            .clip(shape)
            .background(if (selected) DesignColors.Accent else Color.Transparent)
            .border(1.dp, if (selected) DesignColors.Accent else DesignColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun EqPresetDropdown(bands: List<Float>, onSelect: (List<Float>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val match = EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }
    val label = stringResource(match?.label ?: Res.string.sound_preset_custom)
    Box(DialogPad.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, DesignColors.Outline, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
            Text("▾", style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EQ_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(preset.label),
                            style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
                        )
                    },
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

private fun trimFloat(v: Float): String {
    val x = round(v * 100f) / 100f
    val asInt = x.toInt()
    return if (abs(x - asInt) < 0.001f) asInt.toString() else x.toString()
}

private fun bandsMatch(a: List<Float>, b: List<Float>): Boolean {
    if (a.size != b.size) return false
    return a.indices.all { abs(a[it] - b[it]) < 0.2f }
}
