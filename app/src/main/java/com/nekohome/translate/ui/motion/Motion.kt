package com.nekohome.translate.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

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

/** Shared spring tunings so the whole app moves with one personality. */
object Springs {
    /** Playful overshoot: presses, selections, swatches, the swap button. */
    fun <T> bouncy(stiffness: Float = Spring.StiffnessMedium): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.5f, stiffness = stiffness)

    /** A little life without wobble: tab content, list placement, expanding sections. */
    fun <T> gentle(stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = stiffness)

    /** No overshoot: sizes that change continuously (streaming text) and colors. */
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
 */
fun Modifier.bounceClick(interactionSource: InteractionSource, pressedScale: Float = 0.9f): Modifier =
    composed {
        if (LocalReduceMotion.current) return@composed this
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "bounceClick",
        )
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }
