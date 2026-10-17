package com.nekohome.translate.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `true` when the user turned animations off (开发者选项 / 无障碍「移除动画」, i.e.
 * `ANIMATOR_DURATION_SCALE == 0`). Provided once at the root by the theme.
 */
val LocalReduceMotion = compositionLocalOf { false }

/** Reads the system animator scale and keeps it updated while composed. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read(): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = read() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

/**
 * Shared spring tunings so the whole app moves with one personality.
 *
 * Rule of thumb: overshoot only on small things (icons, buttons, swatches). Anything that moves a
 * large area of the screen (tab content, cards expanding, list rows) settles without bounce, so a
 * spring overshoot never forces extra layout passes over big subtrees.
 */
object Springs {
    /** Playful overshoot for small elements only: presses, selections, swatches, the swap button. */
    fun <T> bouncy(stiffness: Float = Spring.StiffnessMedium): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = stiffness)

    /** Screen-level transitions (tab content): quick, with a barely visible settle. */
    fun <T> transition(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = stiffness)

    /** Layout changes (expand / collapse, list placement): fast and critically damped, no wobble. */
    fun <T> gentle(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

    /** No overshoot: fades, sizes that change continuously, colors. */
    fun <T> smooth(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)
}

/** [spec], or an instant snap when the user disabled animations. */
@Composable
@ReadOnlyComposable
fun <T> motion(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spec

/** Same as [motion] for APIs that take a plain [AnimationSpec]. */
@Composable
@ReadOnlyComposable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spec

/**
 * Squishes the element while pressed and springs back with a small overshoot on release.
 * Pass the same [interactionSource] to the button/clickable so presses are observed.
 *
 * Implemented as a [Modifier.Node]: presses are collected in the node's own coroutine and the
 * scale is read only inside the graphics-layer block, so a press animates in the draw phase
 * without recomposing (or even re-measuring) anything.
 */
fun Modifier.bounceClick(interactionSource: InteractionSource, pressedScale: Float = 0.9f): Modifier =
    this then BounceClickElement(interactionSource, pressedScale)

private data class BounceClickElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
) : ModifierNodeElement<BounceClickNode>() {
    override fun create() = BounceClickNode(interactionSource, pressedScale)

    override fun update(node: BounceClickNode) = node.update(interactionSource, pressedScale)

    override fun InspectorInfo.inspectableProperties() {
        name = "bounceClick"
        properties["pressedScale"] = pressedScale
    }
}

private class BounceClickNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {

    private val scale = Animatable(1f)
    private var collectJob: Job? = null
    private var animJob: Job? = null

    // Allocated once; reading scale.value here invalidates only this layer, never composition.
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val s = scale.value
        scaleX = s
        scaleY = s
    }

    override fun onAttach() = collectPresses()

    override fun onDetach() {
        collectJob = null
        animJob = null
    }

    fun update(source: InteractionSource, pressed: Float) {
        pressedScale = pressed
        if (source != interactionSource) {
            interactionSource = source
            collectJob?.cancel()
            if (isAttached) collectPresses()
        }
    }

    private fun collectPresses() {
        collectJob = coroutineScope.launch {
            val presses = ArrayList<PressInteraction.Press>(1)
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    else -> return@collect
                }
                animateTo(if (presses.isNotEmpty()) pressedScale else 1f)
            }
        }
    }

    private fun animateTo(target: Float) {
        animJob?.cancel()
        animJob = coroutineScope.launch {
            if (currentValueOf(LocalReduceMotion)) {
                scale.snapTo(1f)
            } else {
                scale.animateTo(target, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}
