package com.nekohome.translate.ui.theme

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.nekohome.translate.data.ThemeSeed
import java.util.concurrent.ConcurrentHashMap

/** Every Material 3 color role as packed ARGB, generated from one seed. Pure data (JVM-testable). */
data class SchemeArgb(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val background: Int, val onBackground: Int,
    val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
    val surfaceBright: Int, val surfaceDim: Int,
    val surfaceContainer: Int, val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
    val surfaceContainerLow: Int, val surfaceContainerLowest: Int,
    val primaryFixed: Int, val primaryFixedDim: Int, val onPrimaryFixed: Int, val onPrimaryFixedVariant: Int,
    val secondaryFixed: Int, val secondaryFixedDim: Int, val onSecondaryFixed: Int, val onSecondaryFixedVariant: Int,
    val tertiaryFixed: Int, val tertiaryFixedDim: Int, val onTertiaryFixed: Int, val onTertiaryFixedVariant: Int,
)

/**
 * Seed → full M3 scheme using Material Color Utilities (HCT color space + tonal palettes),
 * via the Kotlin port `com.materialkolor:material-color-utilities`. Uses the 2021 spec,
 * i.e. the same roles/tones as Material You on Android 12–15.
 */
object SeedSchemes {
    private val cache = ConcurrentHashMap<Triple<Int, Boolean, Boolean>, SchemeArgb>()

    fun of(seed: ThemeSeed, dark: Boolean): SchemeArgb = generate(seed.argb, dark, seed.neutral)

    fun generate(seedArgb: Int, dark: Boolean, neutral: Boolean = false): SchemeArgb =
        cache.getOrPut(Triple(seedArgb, dark, neutral)) {
            val hct = Hct.fromInt(seedArgb)
            val scheme: DynamicScheme = if (neutral) {
                SchemeNeutral(hct, dark, 0.0, ColorSpec.SpecVersion.SPEC_2021, DynamicScheme.Platform.PHONE)
            } else {
                SchemeTonalSpot(hct, dark, 0.0, ColorSpec.SpecVersion.SPEC_2021, DynamicScheme.Platform.PHONE)
            }
            scheme.toArgb()
        }

    private fun DynamicScheme.toArgb() = SchemeArgb(
        primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer, inversePrimary = inversePrimary,
        secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface, surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint, inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
        outline = outline, outlineVariant = outlineVariant, scrim = scrim,
        surfaceBright = surfaceBright, surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest, surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = primaryFixed, primaryFixedDim = primaryFixedDim, onPrimaryFixed = onPrimaryFixed,
        onPrimaryFixedVariant = onPrimaryFixedVariant,
        secondaryFixed = secondaryFixed, secondaryFixedDim = secondaryFixedDim, onSecondaryFixed = onSecondaryFixed,
        onSecondaryFixedVariant = onSecondaryFixedVariant,
        tertiaryFixed = tertiaryFixed, tertiaryFixedDim = tertiaryFixedDim, onTertiaryFixed = onTertiaryFixed,
        onTertiaryFixedVariant = onTertiaryFixedVariant,
    )
}
