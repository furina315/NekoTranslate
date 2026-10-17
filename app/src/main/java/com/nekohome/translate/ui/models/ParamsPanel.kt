package com.nekohome.translate.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.ui.MainUiState
import com.nekohome.translate.ui.MainViewModel
import com.nekohome.translate.ui.common.Format
import kotlin.math.abs
import kotlin.math.roundToInt

private val MAX_TOKEN_OPTIONS = listOf(256, 512, 1024, 2048, 4096, 8192)
private val CONTEXT_OPTIONS = listOf(1024, 2048, 4096, 8192, 16384, 32768)

/** Parameter editor for one model: profile, prompt template, sampling and load parameters. */
@Composable
fun ParamsPanel(model: LocalModel, state: MainUiState, vm: MainViewModel) {
    val params = state.paramsFor(model)
    val profile = state.profileFor(model)
    val sampling = params.sampling(profile)
    val load = params.loadParams()
    val busy = state.isLoading || state.isTranslating

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.needsReload(model)) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.models_reload_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.loadModel(model) }, enabled = !busy) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.models_reload))
                        }
                    }
                }
            }

            // ---------------- Profile ----------------
            SectionLabel(stringResource(R.string.models_section_profile))
            ProfileSelector(
                autoLabel = stringResource(R.string.models_profile_auto, model.profile.displayName),
                selectedId = params.profileId,
                onSelect = { vm.setProfileOverride(model, it) },
            )
            if (profile.isCustom) {
                OutlinedTextField(
                    value = params.customTemplate ?: BuiltInProfiles.CUSTOM.promptTemplate,
                    onValueChange = { vm.setCustomTemplate(model, it) },
                    label = { Text(stringResource(R.string.models_custom_template)) },
                    supportingText = { Text(stringResource(R.string.models_custom_template_help)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (params.customTemplate != null) {
                    TextButton(onClick = { vm.setCustomTemplate(model, null) }) {
                        Text(stringResource(R.string.models_custom_template_reset))
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ---------------- Sampling ----------------
            SectionLabel(stringResource(R.string.models_section_sampling))
            ParamSlider(
                label = stringResource(R.string.models_temperature),
                valueText = if (sampling.temperature <= 0f) {
                    "0（${stringResource(R.string.models_greedy)}）"
                } else {
                    Format.decimal(sampling.temperature)
                },
                isDefault = params.temperature == null,
                value = sampling.temperature.coerceIn(0f, 2f),
                range = 0f..2f,
                steps = 39,
                onChange = { vm.setTemperature(model, it) },
            )
            ParamSlider(
                label = stringResource(R.string.models_top_p),
                valueText = if (sampling.topP >= 1f) "1（${stringResource(R.string.models_off)}）" else Format.decimal(sampling.topP),
                isDefault = params.topP == null,
                value = sampling.topP.coerceIn(0f, 1f),
                range = 0f..1f,
                steps = 19,
                onChange = { vm.setTopP(model, it) },
            )
            ParamSlider(
                label = stringResource(R.string.models_top_k),
                valueText = if (sampling.topK <= 0) "0（${stringResource(R.string.models_off)}）" else sampling.topK.toString(),
                isDefault = params.topK == null,
                value = sampling.topK.coerceIn(0, 100).toFloat(),
                range = 0f..100f,
                steps = 99,
                onChange = { vm.setTopK(model, it.roundToInt()) },
            )
            ParamSlider(
                label = stringResource(R.string.models_repeat_penalty),
                valueText = if (sampling.repeatPenalty <= 1f) "1（${stringResource(R.string.models_off)}）" else Format.decimal(sampling.repeatPenalty),
                isDefault = params.repeatPenalty == null,
                value = sampling.repeatPenalty.coerceIn(1f, 1.5f),
                range = 1f..1.5f,
                steps = 49,
                onChange = { vm.setRepeatPenalty(model, it) },
            )
            ChoiceSlider(
                label = stringResource(R.string.models_max_tokens),
                options = MAX_TOKEN_OPTIONS,
                current = sampling.maxTokens,
                isDefault = params.maxTokens == null,
                onChange = { vm.setMaxTokens(model, it) },
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ---------------- Load ----------------
            SectionLabel(stringResource(R.string.models_section_load))
            Text(
                stringResource(R.string.models_section_load_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val trainCtx = model.contextLength
            val ctxOptions = CONTEXT_OPTIONS
                .filter { trainCtx == null || it <= trainCtx }
                .ifEmpty { CONTEXT_OPTIONS.take(1) }
            ChoiceSlider(
                label = stringResource(R.string.models_context),
                options = ctxOptions,
                current = load.nCtx,
                isDefault = params.nCtx == null,
                onChange = { vm.setContext(model, it) },
            )
            val maxThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
            if (maxThreads >= 2) {
                ParamSlider(
                    label = stringResource(R.string.models_threads),
                    valueText = load.nThreads.toString(),
                    isDefault = params.nThreads == null,
                    value = load.nThreads.coerceIn(1, maxThreads).toFloat(),
                    range = 1f..maxThreads.toFloat(),
                    steps = (maxThreads - 2).coerceAtLeast(0),
                    onChange = { vm.setThreads(model, it.roundToInt()) },
                )
            }

            OutlinedButton(
                onClick = { vm.resetParams(model) },
                enabled = params.hasValueOverrides,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Outlined.Restore, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.models_reset_defaults))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ProfileSelector(autoLabel: String, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = BuiltInProfiles.byId(selectedId)?.displayName ?: autoLabel
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val choices: List<Pair<String?, String>> =
                listOf<Pair<String?, String>>(null to autoLabel) + BuiltInProfiles.ALL.map { it.id to it.displayName }
            choices.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    trailingIcon = { if (id == selectedId) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ParamHeader(label: String, valueText: String, isDefault: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            if (isDefault) "$valueText · ${stringResource(R.string.models_default_suffix)}" else valueText,
            style = MaterialTheme.typography.labelLarge,
            color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    isDefault: Boolean,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        ParamHeader(label, valueText, isDefault)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** Slider over a fixed list of values; shows the actual value even if it is not in [options]. */
@Composable
private fun ChoiceSlider(
    label: String,
    options: List<Int>,
    current: Int,
    isDefault: Boolean,
    onChange: (Int) -> Unit,
) {
    Column {
        ParamHeader(label, current.toString(), isDefault)
        if (options.size >= 2) {
            val index = options.indices.minByOrNull { abs(options[it] - current) } ?: 0
            Slider(
                value = index.toFloat(),
                onValueChange = { v ->
                    val picked = options[v.roundToInt().coerceIn(0, options.lastIndex)]
                    if (picked != current) onChange(picked)
                },
                valueRange = 0f..options.lastIndex.toFloat(),
                steps = (options.size - 2).coerceAtLeast(0),
            )
        }
    }
}
