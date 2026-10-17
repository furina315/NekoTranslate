package com.nekohome.translate.ui.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.nekohome.translate.data.ThemeSeed
import com.nekohome.translate.ui.motion.LocalReduceMotion
import com.nekohome.translate.ui.motion.rememberReduceMotion

/** M3 color scheme generated from a preset [seed] (HCT tonal palettes). */
fun seedColorScheme(seed: ThemeSeed, dark: Boolean): ColorScheme =
    SeedSchemes.of(seed, dark).toColorScheme(dark)

private fun SchemeArgb.toColorScheme(dark: Boolean): ColorScheme {
    fun c(argb: Int) = Color(argb)
    return if (dark) {
        darkColorScheme(
            primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer),
            onPrimaryContainer = c(onPrimaryContainer), inversePrimary = c(inversePrimary),
            secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer),
            onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer),
            onTertiaryContainer = c(onTertiaryContainer),
            background = c(background), onBackground = c(onBackground),
            surface = c(surface), onSurface = c(onSurface), surfaceVariant = c(surfaceVariant),
            onSurfaceVariant = c(onSurfaceVariant), surfaceTint = c(surfaceTint),
            inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
            error = c(error), onError = c(onError), errorContainer = c(errorContainer),
            onErrorContainer = c(onErrorContainer),
            outline = c(outline), outlineVariant = c(outlineVariant), scrim = c(scrim),
            surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
            surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainerLowest = c(surfaceContainerLowest),
            primaryFixed = c(primaryFixed), primaryFixedDim = c(primaryFixedDim), onPrimaryFixed = c(onPrimaryFixed),
            onPrimaryFixedVariant = c(onPrimaryFixedVariant),
            secondaryFixed = c(secondaryFixed), secondaryFixedDim = c(secondaryFixedDim),
            onSecondaryFixed = c(onSecondaryFixed), onSecondaryFixedVariant = c(onSecondaryFixedVariant),
            tertiaryFixed = c(tertiaryFixed), tertiaryFixedDim = c(tertiaryFixedDim),
            onTertiaryFixed = c(onTertiaryFixed), onTertiaryFixedVariant = c(onTertiaryFixedVariant),
        )
    } else {
        lightColorScheme(
            primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer),
            onPrimaryContainer = c(onPrimaryContainer), inversePrimary = c(inversePrimary),
            secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer),
            onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer),
            onTertiaryContainer = c(onTertiaryContainer),
            background = c(background), onBackground = c(onBackground),
            surface = c(surface), onSurface = c(onSurface), surfaceVariant = c(surfaceVariant),
            onSurfaceVariant = c(onSurfaceVariant), surfaceTint = c(surfaceTint),
            inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
            error = c(error), onError = c(onError), errorContainer = c(errorContainer),
            onErrorContainer = c(onErrorContainer),
            outline = c(outline), outlineVariant = c(outlineVariant), scrim = c(scrim),
            surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
            surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainerLowest = c(surfaceContainerLowest),
            primaryFixed = c(primaryFixed), primaryFixedDim = c(primaryFixedDim), onPrimaryFixed = c(onPrimaryFixed),
            onPrimaryFixedVariant = c(onPrimaryFixedVariant),
            secondaryFixed = c(secondaryFixed), secondaryFixedDim = c(secondaryFixedDim),
            onSecondaryFixed = c(onSecondaryFixed), onSecondaryFixedVariant = c(onSecondaryFixedVariant),
            tertiaryFixed = c(tertiaryFixed), tertiaryFixedDim = c(tertiaryFixedDim),
            onTertiaryFixed = c(onTertiaryFixed), onTertiaryFixedVariant = c(onTertiaryFixedVariant),
        )
    }
}

/**
 * Material 3 theme: Material You dynamic color on Android 12+ when enabled, otherwise a scheme
 * generated from the chosen preset [seed]. Scheme changes (seed, light/dark, dynamic on/off)
 * cross-fade with one short tween; instant when the system disables animations.
 */
@Composable
fun NekoTranslateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seed: ThemeSeed = ThemeSeed.DEFAULT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val target = remember(useDynamic, darkTheme, seed, context) {
        when {
            useDynamic && darkTheme -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> seedColorScheme(seed, darkTheme)
        }
    }
    val reduceMotion = rememberReduceMotion()
    val colorScheme = animateColorScheme(target, animate = !reduceMotion)
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/**
 * Cross-fades from the previous scheme to [target] with a single 0..1 progress value.
 *
 * Idle cost is zero: when no transition is running this returns the exact [target] instance, so
 * MaterialTheme sees the same object and nothing below recomposes. (The previous version kept
 * ~36 `animateColorAsState` animations alive and rebuilt the scheme with `copy()` on every
 * recomposition, which handed MaterialTheme a new ColorScheme each time.)
 *
 * While a transition runs (≈300 ms) readers of the scheme recompose once per frame — the
 * unavoidable price of animating every color role — and are left alone afterwards.
 */
@Composable
private fun animateColorScheme(target: ColorScheme, animate: Boolean): ColorScheme {
    val transition = remember { SchemeTransition(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target, animate) {
        if (target === transition.to) return@LaunchedEffect
        // Start from whatever is on screen right now (handles a change mid-transition).
        transition.from = lerpScheme(transition.from, transition.to, progress.value)
        transition.to = target
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = SCHEME_FADE_MS, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
        transition.from = target
    }
    val p = progress.value
    return if (p >= 1f) transition.to else lerpScheme(transition.from, transition.to, p)
}

private const val SCHEME_FADE_MS = 300

/** [to] is snapshot state (an instant switch with animations off must still recompose); [from] is plain. */
private class SchemeTransition(initial: ColorScheme) {
    var from: ColorScheme = initial
    var to: ColorScheme by mutableStateOf(initial)
}

internal fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return a
    if (t >= 1f || a === b) return b
    fun l(x: Color, y: Color) = if (x == y) y else lerp(x, y, t)
    return b.copy(
        primary = l(a.primary, b.primary),
        onPrimary = l(a.onPrimary, b.onPrimary),
        primaryContainer = l(a.primaryContainer, b.primaryContainer),
        onPrimaryContainer = l(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = l(a.inversePrimary, b.inversePrimary),
        secondary = l(a.secondary, b.secondary),
        onSecondary = l(a.onSecondary, b.onSecondary),
        secondaryContainer = l(a.secondaryContainer, b.secondaryContainer),
        onSecondaryContainer = l(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = l(a.tertiary, b.tertiary),
        onTertiary = l(a.onTertiary, b.onTertiary),
        tertiaryContainer = l(a.tertiaryContainer, b.tertiaryContainer),
        onTertiaryContainer = l(a.onTertiaryContainer, b.onTertiaryContainer),
        background = l(a.background, b.background),
        onBackground = l(a.onBackground, b.onBackground),
        surface = l(a.surface, b.surface),
        onSurface = l(a.onSurface, b.onSurface),
        surfaceVariant = l(a.surfaceVariant, b.surfaceVariant),
        onSurfaceVariant = l(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceTint = l(a.surfaceTint, b.surfaceTint),
        inverseSurface = l(a.inverseSurface, b.inverseSurface),
        inverseOnSurface = l(a.inverseOnSurface, b.inverseOnSurface),
        error = l(a.error, b.error),
        onError = l(a.onError, b.onError),
        errorContainer = l(a.errorContainer, b.errorContainer),
        onErrorContainer = l(a.onErrorContainer, b.onErrorContainer),
        outline = l(a.outline, b.outline),
        outlineVariant = l(a.outlineVariant, b.outlineVariant),
        scrim = l(a.scrim, b.scrim),
        surfaceBright = l(a.surfaceBright, b.surfaceBright),
        surfaceDim = l(a.surfaceDim, b.surfaceDim),
        surfaceContainer = l(a.surfaceContainer, b.surfaceContainer),
        surfaceContainerHigh = l(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = l(a.surfaceContainerHighest, b.surfaceContainerHighest),
        surfaceContainerLow = l(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainerLowest = l(a.surfaceContainerLowest, b.surfaceContainerLowest),
        primaryFixed = l(a.primaryFixed, b.primaryFixed),
        primaryFixedDim = l(a.primaryFixedDim, b.primaryFixedDim),
        onPrimaryFixed = l(a.onPrimaryFixed, b.onPrimaryFixed),
        onPrimaryFixedVariant = l(a.onPrimaryFixedVariant, b.onPrimaryFixedVariant),
        secondaryFixed = l(a.secondaryFixed, b.secondaryFixed),
        secondaryFixedDim = l(a.secondaryFixedDim, b.secondaryFixedDim),
        onSecondaryFixed = l(a.onSecondaryFixed, b.onSecondaryFixed),
        onSecondaryFixedVariant = l(a.onSecondaryFixedVariant, b.onSecondaryFixedVariant),
        tertiaryFixed = l(a.tertiaryFixed, b.tertiaryFixed),
        tertiaryFixedDim = l(a.tertiaryFixedDim, b.tertiaryFixedDim),
        onTertiaryFixed = l(a.onTertiaryFixed, b.onTertiaryFixed),
        onTertiaryFixedVariant = l(a.onTertiaryFixedVariant, b.onTertiaryFixedVariant),
    )
}
