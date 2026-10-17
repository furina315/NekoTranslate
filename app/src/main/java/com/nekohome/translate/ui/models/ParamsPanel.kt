@file:OptIn(ExperimentalMaterial3Api::class)

package com.nekohome.translate.ui.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekohome.translate.R
import com.nekohome.translate.data.BuiltInProfiles
import com.nekohome.translate.data.LocalModel
import com.nekohome.translate.data.ParamScales
import com.nekohome.translate.ui.MainUiState
import com.nekohome.translate.ui.MainViewModel
import com.nekohome.translate.ui.common.Format
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import com.nekohome.translate.ui.motion.motion

/**
 * Parameter editor for one model, as grouped, collapsible cards: Profile / 采样 / 加载参数.
 * Sliders are continuous (no tick marks) and snap to [ParamScales] grids; −/+ buttons step
 * along the same grid; token counts are segmented choices. A `null` override still means
 * "follow the profile default" (shown with a 默认 badge).
 */
@Composable
fun ParamsPanel(model: LocalModel, state: MainUiState, vm: MainViewModel) {
    val params = state.paramsFor(model)
    val profile = state.profileFor(model)
    val sampling = params.sampling(profile)
    val load = params.loadParams()
    val busy = state.isLoading || state.isTranslating
    val reduceMotion = LocalReduceMotion.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = state.needsReload(model),
            enter = if (reduceMotion) fadeIn(motion(Springs.smooth())) else
                expandVertically(Springs.gentle()) + fadeIn(Springs.smooth()),
            exit = if (reduceMotion) fadeOut(motion(Springs.smooth())) else
                shrinkVertically(Springs.gentle()) + fadeOut(Springs.smooth()),
        ) {
            ReloadBanner(enabled = !busy, onReload = { vm.loadModel(model) })
        }

        // ---------------- Profile ----------------
        ParamSection(
            title = stringResource(R.string.models_section_profile),
            icon = Icons.Outlined.Description,
            saveKey = "profile",
        ) {
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
        }

        // ---------------- Sampling ----------------
        ParamSection(
            title = stringResource(R.string.models_section_sampling),
            icon = Icons.Outlined.Tune,
            saveKey = "sampling",
        ) {
            val greedy = stringResource(R.string.models_greedy)
            val off = stringResource(R.string.models_off)

            LinearParam(
                label = stringResource(R.string.models_temperature),
                valueText = if (sampling.temperature <= 0f) "0（$greedy）" else Format.decimal(sampling.temperature),
                isDefault = params.temperature == null,
                value = sampling.temperature,
                scale = ParamScales.TEMPERATURE,
                onChange = { vm.setTemperature(model, it) },
            )
            LinearParam(
                label = stringResource(R.string.models_top_p),
                valueText = if (sampling.topP >= 1f) "1（$off）" else Format.decimal(sampling.topP),
                isDefault = params.topP == null,
                value = sampling.topP,
                scale = ParamScales.TOP_P,
                onChange = { vm.setTopP(model, it) },
            )
            StopsParam(
                label = stringResource(R.string.models_top_k),
                valueText = if (sampling.topK <= 0) "0（$off）" else sampling.topK.toString(),
                isDefault = params.topK == null,
                stops = ParamScales.TOP_K_STOPS,
                current = sampling.topK,
                onChange = { vm.setTopK(model, it) },
            )
            LinearParam(
                label = stringResource(R.string.models_repeat_penalty),
                valueText = if (sampling.repeatPenalty <= 1f) "1（$off）" else Format.decimal(sampling.repeatPenalty),
                isDefault = params.repeatPenalty == null,
                value = sampling.repeatPenalty,
                scale = ParamScales.REPEAT_PENALTY,
                onChange = { vm.setRepeatPenalty(model, it) },
            )
            SegmentedParam(
                label = stringResource(R.string.models_max_tokens),
                options = ParamScales.MAX_TOKEN_OPTIONS,
                current = sampling.maxTokens,
                isDefault = params.maxTokens == null,
                onChange = { vm.setMaxTokens(model, it) },
            )
        }

        // ---------------- Load ----------------
        ParamSection(
            title = stringResource(R.string.models_section_load),
            subtitle = stringResource(R.string.models_section_load_hint),
            icon = Icons.Outlined.Memory,
            saveKey = "load",
        ) {
            SegmentedParam(
                label = stringResource(R.string.models_context),
                options = ParamScales.contextOptions(model.contextLength),
                current = load.nCtx,
                isDefault = params.nCtx == null,
                onChange = { vm.setContext(model, it) },
            )
            val maxThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
            if (maxThreads >= 2) {
                val threads = load.nThreads.coerceIn(1, maxThreads)
                StepperParam(
                    label = stringResource(R.string.models_threads),
                    value = threads,
                    isDefault = params.nThreads == null,
                    canMinus = threads > 1,
                    canPlus = threads < maxThreads,
                    onChange = { vm.setThreads(model, it) },
                )
            }
        }

        val resetSource = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = { vm.resetParams(model) },
            enabled = params.hasValueOverrides,
            interactionSource = resetSource,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .bounceClick(resetSource),
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.models_reset_defaults))
        }
    }
}

@Composable
private fun ReloadBanner(enabled: Boolean, onReload: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                stringResource(R.string.models_reload_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            val src = remember { MutableInteractionSource() }
            TextButton(onClick = onReload, enabled = enabled, interactionSource = src, modifier = Modifier.bounceClick(src)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.models_reload))
            }
        }
    }
}

/** A titled card whose body expands / collapses with a spring. */
@Composable
private fun ParamSection(
    title: String,
    icon: ImageVector,
    saveKey: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(saveKey) { mutableStateOf(true) }
    // Read only in the graphics layer below, so the spin doesn't recompose the section.
    val chevron = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion(Springs.bouncy()),
        label = "chevron",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.animateContentSize(motion(Springs.gentle()))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.models_section_collapse else R.string.models_section_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron.value },
                )
            }
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                ) {
                    content()
                }
            }
        }
    }
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

/** Label · 默认 badge · value, on one line. */
@Composable
private fun ParamHeader(
    label: String,
    valueText: String,
    isDefault: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        DefaultBadge(isDefault)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        } else {
            ValueText(valueText, isDefault)
        }
    }
}

@Composable
private fun ValueText(text: String, isDefault: Boolean, modifier: Modifier = Modifier, align: TextAlign = TextAlign.End) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun DefaultBadge(visible: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) fadeIn(motion(Springs.smooth())) else scaleIn(Springs.bouncy(), 0.6f) + fadeIn(Springs.smooth()),
        exit = if (reduceMotion) fadeOut(motion(Springs.smooth())) else scaleOut(Springs.smooth(), 0.6f) + fadeOut(Springs.smooth()),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                stringResource(R.string.models_default_suffix),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Small tonal −/+ button with a press bounce. */
@Composable
private fun NudgeButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = src,
        modifier = Modifier
            .size(36.dp)
            .bounceClick(src, pressedScale = 0.82f),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/** − [continuous slider, no ticks] + ; callers snap the values. */
@Composable
private fun SliderRow(
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    onSlide: (Float) -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        NudgeButton(Icons.Filled.Remove, stringResource(R.string.models_decrease), canMinus, onMinus)
        Slider(
            value = sliderValue.coerceIn(range.start, range.endInclusive),
            onValueChange = onSlide,
            valueRange = range,
            steps = 0,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        NudgeButton(Icons.Filled.Add, stringResource(R.string.models_increase), canPlus, onPlus)
    }
}

@Composable
private fun LinearParam(
    label: String,
    valueText: String,
    isDefault: Boolean,
    value: Float,
    scale: ParamScales.Linear,
    onChange: (Float) -> Unit,
) {
    fun emit(v: Float) { if (v != value) onChange(v) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ParamHeader(label, valueText, isDefault)
        SliderRow(
            sliderValue = value,
            range = scale.min..scale.max,
            onSlide = { emit(scale.snap(it)) },
            canMinus = scale.canNudge(value, -1),
            canPlus = scale.canNudge(value, +1),
            onMinus = { emit(scale.nudge(value, -1)) },
            onPlus = { emit(scale.nudge(value, +1)) },
        )
    }
}

/** Slider over a short list of hand-picked stops (positions are evenly spaced, no ticks). */
@Composable
private fun StopsParam(
    label: String,
    valueText: String,
    isDefault: Boolean,
    stops: List<Int>,
    current: Int,
    onChange: (Int) -> Unit,
) {
    fun emit(v: Int) { if (v != current) onChange(v) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ParamHeader(label, valueText, isDefault)
        SliderRow(
            sliderValue = ParamScales.nearestIndex(stops, current).toFloat(),
            range = 0f..stops.lastIndex.toFloat(),
            onSlide = { emit(ParamScales.optionAt(stops, it)) },
            canMinus = current > stops.first(),
            canPlus = current < stops.last(),
            onMinus = { emit(ParamScales.stepOption(stops, current, -1)) },
            onPlus = { emit(ParamScales.stepOption(stops, current, +1)) },
        )
    }
}

/** Token-count choice as a segmented button row ("512 · 1K · 2K …"). */
@Composable
private fun SegmentedParam(
    label: String,
    options: List<Int>,
    current: Int,
    isDefault: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ParamHeader(label, current.toString(), isDefault)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == current,
                    onClick = { if (option != current) onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    icon = {},
                    label = {
                        Text(
                            ParamScales.tokenLabel(option),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

/** Integer stepper: label · 默认 … [−] value [+]. */
@Composable
private fun StepperParam(
    label: String,
    value: Int,
    isDefault: Boolean,
    canMinus: Boolean,
    canPlus: Boolean,
    onChange: (Int) -> Unit,
) {
    ParamHeader(label, value.toString(), isDefault) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NudgeButton(Icons.Filled.Remove, stringResource(R.string.models_decrease), canMinus) { onChange(value - 1) }
            ValueText(value.toString(), isDefault, Modifier.widthIn(min = 40.dp).padding(horizontal = 4.dp), TextAlign.Center)
            NudgeButton(Icons.Filled.Add, stringResource(R.string.models_increase), canPlus) { onChange(value + 1) }
        }
    }
}
