package com.nekohome.translate.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.diag.NativeCrashRecord
import com.nekohome.translate.diag.NativeCrashText
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.motion

/** What 设置 → 诊断 shows about native crashes. [records] is null while loading. */
data class NativeCrashUiState(val supported: Boolean, val records: List<NativeCrashRecord>?)

/**
 * 「原生崩溃记录」 row in 设置 → 诊断: summary line, expands (spring, no bounce) into one card per
 * crash; a card expands again to show abort message, causes and the crashing thread's backtrace.
 */
@Composable
fun NativeCrashSection(state: NativeCrashUiState, onClear: () -> Unit) {
    val records = state.records.orEmpty()
    val hasRecords = state.supported && records.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val open = expanded && hasRecords
    val reduceMotion = LocalReduceMotion.current
    val arrow by animateFloatAsState(if (open) 180f else 0f, motion(Springs.smooth()), label = "crashArrow")
    val summary = when {
        !state.supported -> stringResource(R.string.diag_native_unsupported)
        state.records == null -> stringResource(R.string.diag_native_loading)
        records.isEmpty() -> stringResource(R.string.diag_native_empty)
        else -> stringResource(R.string.diag_native_summary, records.size, NativeCrashText.displayTime(records.first().timestampMs))
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.diag_native)) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(Icons.Outlined.Warning, contentDescription = null) },
        trailingContent = if (hasRecords) ({
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(arrow))
        }) else null,
        modifier = if (hasRecords) Modifier.clickable { expanded = !expanded } else Modifier,
    )
    AnimatedVisibility(
        visible = open,
        enter = if (reduceMotion) EnterTransition.None else expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
        exit = if (reduceMotion) ExitTransition.None else shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (r in records) CrashCard(r)
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                Text(stringResource(R.string.diag_native_clear), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CrashCard(r: NativeCrashRecord) {
    var open by rememberSaveable(r.timestampMs) { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val t = r.tombstone
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    NativeCrashText.displayTime(r.timestampMs),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (t != null) NativeCrashText.signalLabel(t) else stringResource(R.string.diag_native_no_signal),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (t != null) {
                NativeCrashText.faultAddress(t)?.let {
                    Text(stringResource(R.string.diag_native_fault, it), style = mono)
                }
                NativeCrashText.relevantFrame(t)?.let {
                    Text(NativeCrashText.frameSummary(it), style = mono, color = MaterialTheme.colorScheme.primary)
                }
            }
            NativeCrashText.statusLine(r)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = open,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val variant = MaterialTheme.colorScheme.onSurfaceVariant
                        Text("pid ${r.pid}" + if (r.processName.isNotBlank()) " · ${r.processName}" else "", style = mono, color = variant)
                        if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall, color = variant)
                        if (t != null) {
                            if (t.abortMessage.isNotBlank()) {
                                Text(stringResource(R.string.diag_native_abort, t.abortMessage.trim()), style = mono)
                            }
                            if (t.causes.isNotEmpty()) {
                                Text(stringResource(R.string.diag_native_cause, t.causes.joinToString("; ")), style = MaterialTheme.typography.bodySmall)
                            }
                            t.crashingThread?.let { th ->
                                Text(stringResource(R.string.diag_native_thread, "${th.id} ${th.name}"), style = mono, color = variant)
                            }
                            val bt = NativeCrashText.backtrace(t)
                            Text(
                                bt.ifBlank { stringResource(R.string.diag_native_no_backtrace) },
                                style = mono,
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
            if (!open) {
                Text(
                    stringResource(R.string.diag_native_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
