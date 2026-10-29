package com.nekohome.translate.ui.quick

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekohome.translate.R
import com.nekohome.translate.tts.SpeakTarget
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.Springs
import com.nekohome.translate.ui.motion.bounceClick
import com.nekohome.translate.ui.motion.motion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The quick-translate bottom card over a translucent window: scrim tap, back or a downward swipe
 * closes it (spring animations in and out); [onClosed] runs once the exit animation finished.
 */
@Composable
fun QuickTranslateScreen(
    vm: QuickTranslateViewModel,
    onClosed: () -> Unit,
    onCopy: (String) -> Unit,
    onReplace: (String) -> Boolean,
    onOpenApp: (source: String, translation: String?, targetCode: String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissPx = with(density) { 120.dp.toPx() }

    fun dismiss() {
        vm.dismiss()
        visible.targetState = false
    }
    BackHandler { dismiss() }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onClosed()
    }

    Box(Modifier.fillMaxSize()) {
        // Scrim: tapping outside the card closes it.
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(motion(Springs.smooth())),
            exit = fadeOut(motion(Springs.smooth())),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() },
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))) { it } +
                fadeIn(motion(Springs.smooth())),
            exit = slideOutVertically(motion(Springs.smooth(Spring.StiffnessMedium))) { it } +
                fadeOut(motion(Springs.smooth(Spring.StiffnessMedium))),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                        },
                        onDragStopped = { velocity ->
                            if (dragOffset.value > dismissPx || velocity > 2500f) {
                                dismiss()
                            } else {
                                dragOffset.animateTo(
                                    0f,
                                    if (reduceMotion) spring(stiffness = Spring.StiffnessHigh) else Springs.bouncy(Spring.StiffnessMediumLow),
                                )
                            }
                        },
                    )
                    // Swallow taps so they don't reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                QuickCard(
                    state = state,
                    output = output,
                    speaking = speaking == SpeakTarget.OUTPUT,
                    onClose = ::dismiss,
                    onTarget = vm::setTarget,
                    onRetry = vm::run,
                    onCopy = { onCopy(output) },
                    onSpeak = vm::toggleSpeak,
                    onReplace = { if (onReplace(output)) dismiss() },
                    onOpenApp = {
                        onOpenApp(state.source, output.takeIf { state.phase == QuickPhase.DONE }, state.targetCode)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickCard(
    state: QuickUiState,
    output: String,
    speaking: Boolean,
    onClose: () -> Unit,
    onTarget: (String) -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onReplace: () -> Unit,
    onOpenApp: () -> Unit,
) {
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        // Drag handle
        Box(
            Modifier
                .padding(top = 10.dp, bottom = 4.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.quick_label), style = MaterialTheme.typography.titleSmall)
                state.modelName?.let {
                    Text(
                        stringResource(R.string.quick_model_line, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val closeSrc = remember { MutableInteractionSource() }
            IconButton(onClick = onClose, interactionSource = closeSrc, modifier = Modifier.bounceClick(closeSrc, pressedScale = 0.85f)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.quick_close))
            }
        }

        // Source (collapsible)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Column(
                Modifier
                    .clickable { sourceExpanded = !sourceExpanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .animateContentSize(motion(Springs.gentle())),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.quick_source),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (sourceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (sourceExpanded) R.string.quick_collapse else R.string.quick_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (sourceExpanded) {
                    SelectionContainer {
                        Text(
                            state.source,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    Text(state.source, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Language pair
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                stringResource(R.string.quick_auto_source) + "  →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            TargetPicker(state = state, onTarget = onTarget)
        }

        // Result
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .heightIn(min = 72.dp, max = 300.dp)
                .animateContentSize(motion(Springs.smooth(Spring.StiffnessMedium))),
        ) {
            AnimatedContent(
                targetState = output.isEmpty(),
                transitionSpec = { fadeIn(Springs.smooth()) togetherWith fadeOut(Springs.smooth()) },
                label = "quick-result",
            ) { empty ->
                if (empty) {
                    StatusBlock(state)
                } else {
                    SelectionContainer {
                        Text(
                            output,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
        state.message?.takeIf { output.isNotEmpty() || state.phase == QuickPhase.DONE }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(bottom = 4.dp))
        }

        // Actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            val done = state.phase == QuickPhase.DONE && output.isNotBlank()
            val copySrc = remember { MutableInteractionSource() }
            FilledTonalIconButton(onClick = onCopy, enabled = output.isNotBlank(), interactionSource = copySrc, modifier = Modifier.bounceClick(copySrc, pressedScale = 0.85f)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.quick_copy))
            }
            val speakSrc = remember { MutableInteractionSource() }
            FilledTonalIconButton(onClick = onSpeak, enabled = done, interactionSource = speakSrc, modifier = Modifier.bounceClick(speakSrc, pressedScale = 0.85f)) {
                if (speaking) Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.quick_stop_speak))
                else Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.quick_speak))
            }
            if (state.replaceable) {
                val replaceSrc = remember { MutableInteractionSource() }
                FilledTonalButton(onClick = onReplace, enabled = done, interactionSource = replaceSrc, modifier = Modifier.bounceClick(replaceSrc)) {
                    Icon(Icons.Outlined.FindReplace, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.quick_replace))
                }
            }
            if (state.phase == QuickPhase.FAILED) {
                val retrySrc = remember { MutableInteractionSource() }
                FilledTonalIconButton(onClick = onRetry, interactionSource = retrySrc, modifier = Modifier.bounceClick(retrySrc, pressedScale = 0.85f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.quick_retry))
                }
            }
            Spacer(Modifier.weight(1f))
            val openSrc = remember { MutableInteractionSource() }
            TextButton(onClick = onOpenApp, interactionSource = openSrc, modifier = Modifier.bounceClick(openSrc)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.quick_open_app))
            }
        }
    }
}

@Composable
private fun TargetPicker(state: QuickUiState, onTarget: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val src = remember { MutableInteractionSource() }
        AssistChip(
            onClick = { if (state.targets.isNotEmpty()) open = true },
            enabled = state.targets.isNotEmpty(),
            interactionSource = src,
            modifier = Modifier.bounceClick(src),
            label = { Text(state.target?.nameZh ?: stringResource(R.string.quick_target)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            state.targets.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.label()) },
                    onClick = {
                        open = false
                        onTarget(lang.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(state: QuickUiState) {
    val text = when (state.phase) {
        QuickPhase.PREPARING -> stringResource(R.string.quick_preparing)
        QuickPhase.WAITING_FOR_LOAD -> stringResource(R.string.quick_waiting_model, state.loadSeconds)
        QuickPhase.LOADING_MODEL -> stringResource(R.string.quick_loading_model, state.modelName.orEmpty(), state.loadSeconds)
        QuickPhase.QUEUED -> stringResource(R.string.quick_queued)
        QuickPhase.TRANSLATING -> stringResource(R.string.quick_translating)
        QuickPhase.DONE -> state.message.orEmpty()
        QuickPhase.NO_MODEL, QuickPhase.FAILED -> state.message.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (state.isBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.phase == QuickPhase.FAILED || state.phase == QuickPhase.NO_MODEL) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
