package io.ymusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.ymusic.player.Player
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.sound_bass
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_eq_band
import io.ymusic.ui.resources.sound_pitch
import io.ymusic.ui.resources.sound_preset_bass
import io.ymusic.ui.resources.sound_preset_flat
import io.ymusic.ui.resources.sound_preset_treble
import io.ymusic.ui.resources.sound_preset_voice
import io.ymusic.ui.resources.sound_reset
import io.ymusic.ui.resources.sound_speed
import io.ymusic.ui.resources.sound_title
import io.ymusic.ui.resources.sound_virtualizer
import io.ymusic.ui.resources.sound_volume
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.round

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val EQ_HZ = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

private data class EqPreset(val label: StringResource, val bands: List<Float>)

private val EQ_PRESETS = listOf(
    EqPreset(Res.string.sound_preset_flat, List(10) { 0f }),
    EqPreset(Res.string.sound_preset_bass, listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset(Res.string.sound_preset_treble, listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f)),
    EqPreset(Res.string.sound_preset_voice, listOf(-4f, -3f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
)

@Composable
internal fun AudioFxSheet(player: Player, onDismiss: () -> Unit) {
    val config by player.config.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(stringResource(Res.string.sound_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.sound_volume, (config.volume * 100).toInt()))
                Slider(
                    value = config.volume,
                    onValueChange = { player.setVolume(it) },
                    valueRange = 0f..1f,
                )
                Text(stringResource(Res.string.sound_speed))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEED_PRESETS.forEach { s ->
                        FilterChip(
                            selected = abs(config.speed - s) < 0.02f,
                            onClick = { player.setSpeed(s) },
                            label = { Text(if (s == 1f) "1×" else "${trimFloat(s)}×") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.sound_pitch, trimFloat(config.pitchFactor)))
                Slider(
                    value = config.pitchFactor,
                    onValueChange = { player.setPitch(it) },
                    valueRange = 0.5f..2f,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.sound_eq))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EQ_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = bandsMatch(config.eqBands, preset.bands),
                            onClick = { player.setEqBands(preset.bands) },
                            label = { Text(stringResource(preset.label)) },
                        )
                    }
                }
                EQ_HZ.forEachIndexed { i, label ->
                    val db = config.eqBands.getOrElse(i) { 0f }
                    Text(stringResource(Res.string.sound_eq_band, label, db.toInt()))
                    Slider(
                        value = db,
                        onValueChange = { v ->
                            val next = config.eqBands.toMutableList()
                            while (next.size < 10) next.add(0f)
                            next[i] = v
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
                            player.setPitch(1f)
                            player.setSpeed(1f)
                        },
                    ) { Text(stringResource(Res.string.sound_reset)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cd_close)) }
                }
            }
        }
    }
}

private fun trimFloat(v: Float): String {
    val x = round(v * 100f) / 100f
    val asInt = x.toInt()
    return if (abs(x - asInt) < 0.001f) asInt.toString() else x.toString()
}

private fun bandsMatch(a: List<Float>, b: List<Float>): Boolean {
    if (a.size != b.size) return false
    return a.indices.all { abs(a[it] - b[it]) < 0.2f }
}
