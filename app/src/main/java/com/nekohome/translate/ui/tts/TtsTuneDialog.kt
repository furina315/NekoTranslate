package com.nekohome.translate.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.tts.SpeechPlan
import java.util.Locale

/** 1.0 / 0.75 / 1.25 style label. */
fun rateLabel(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** 语速 / 音调 with snapping stops and −/+ (same feel as the model parameters). */
@Composable
fun TtsTuneDialog(
    rate: Float,
    pitch: Float,
    pitchSupported: Boolean,
    onRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tts_tune)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StopsRow(stringResource(R.string.tts_tune_rate), SpeechPlan.RATE_STOPS, rate, true, onRate)
                StopsRow(stringResource(R.string.tts_tune_pitch), SpeechPlan.PITCH_STOPS, pitch, pitchSupported, onPitch)
                if (!pitchSupported) {
                    Text(
                        stringResource(R.string.tts_tune_pitch_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onRate(1f); onPitch(1f) }) { Text(stringResource(R.string.tts_tune_reset)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tts_tune_done)) } },
    )
}

@Composable
private fun StopsRow(label: String, stops: List<Float>, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val idx = SpeechPlan.nearestIndex(stops, value)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("${rateLabel(stops[idx])}×", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(stops[idx - 1]) }, enabled = enabled && idx > 0) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.models_decrease))
            }
            Slider(
                value = idx.toFloat(),
                onValueChange = { onChange(stops[it.toInt().coerceIn(0, stops.lastIndex)]) },
                valueRange = 0f..stops.lastIndex.toFloat(),
                steps = 0,
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = { onChange(stops[idx + 1]) }, enabled = enabled && idx < stops.lastIndex) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.models_increase))
            }
        }
    }
}
