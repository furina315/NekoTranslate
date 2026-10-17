package com.nekohome.translate.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
 * cross-fade every color role with a spring; instant when the system disables animations.
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
    val colorScheme = animateColorScheme(target, if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessLow))
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** Animates every role of [target]; one spring per role, only running while a change is in flight. */
@Composable
private fun animateColorScheme(target: ColorScheme, spec: AnimationSpec<Color>): ColorScheme {
    @Composable
    fun a(c: Color): Color = animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary),
        onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer),
        onPrimaryContainer = a(target.onPrimaryContainer),
        inversePrimary = a(target.inversePrimary),
        secondary = a(target.secondary),
        onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer),
        onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary),
        onTertiary = a(target.onTertiary),
        tertiaryContainer = a(target.tertiaryContainer),
        onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background),
        onBackground = a(target.onBackground),
        surface = a(target.surface),
        onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant),
        onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceTint = a(target.surfaceTint),
        inverseSurface = a(target.inverseSurface),
        inverseOnSurface = a(target.inverseOnSurface),
        error = a(target.error),
        onError = a(target.onError),
        errorContainer = a(target.errorContainer),
        onErrorContainer = a(target.onErrorContainer),
        outline = a(target.outline),
        outlineVariant = a(target.outlineVariant),
        scrim = a(target.scrim),
        surfaceBright = a(target.surfaceBright),
        surfaceDim = a(target.surfaceDim),
        surfaceContainer = a(target.surfaceContainer),
        surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest),
        surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainerLowest = a(target.surfaceContainerLowest),
    )
}
