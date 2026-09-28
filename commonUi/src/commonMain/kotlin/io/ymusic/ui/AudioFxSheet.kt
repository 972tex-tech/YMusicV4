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

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val EQ_HZ = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

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
                Text("Sound", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("Volume ${(config.volume * 100).toInt()}%")
                Slider(
                    value = config.volume,
                    onValueChange = { player.setVolume(it) },
                    valueRange = 0f..1f,
                )
                Text("Speed")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEED_PRESETS.forEach { s ->
                        FilterChip(
                            selected = kotlin.math.abs(config.speed - s) < 0.02f,
                            onClick = { player.setSpeed(s) },
                            label = { Text(if (s == 1f) "1×" else "$s×") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Pitch ${"%.2f".format(config.pitchFactor)}")
                Slider(
                    value = config.pitchFactor,
                    onValueChange = { player.setPitch(it) },
                    valueRange = 0.5f..2f,
                )
                Spacer(Modifier.height(8.dp))
                Text("Equalizer")
                EQ_HZ.forEachIndexed { i, label ->
                    Text("$label Hz  ${config.eqBands.getOrElse(i) { 0f }.toInt()} dB")
                    Slider(
                        value = config.eqBands.getOrElse(i) { 0f },
                        onValueChange = { v ->
                            val next = config.eqBands.toMutableList()
                            while (next.size < 10) next.add(0f)
                            next[i] = v
                            player.setEqBands(next)
                        },
                        valueRange = -12f..12f,
                    )
                }
                Text("Bass boost ${config.bassBoostDb.toInt()} dB")
                Slider(
                    value = config.bassBoostDb,
                    onValueChange = { player.setBassBoost(it) },
                    valueRange = 0f..12f,
                )
                Text("Virtualizer ${"%.2f".format(config.virtualizer)}")
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
                    ) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
