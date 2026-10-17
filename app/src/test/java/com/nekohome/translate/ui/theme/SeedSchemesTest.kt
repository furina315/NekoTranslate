package com.nekohome.translate.ui.theme

import com.materialkolor.hct.Hct
import com.nekohome.translate.data.ThemeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class SeedSchemesTest {

    @Test
    fun twelveSeedsWithUniqueKeysAndDefault() {
        assertEquals(12, ThemeSeed.entries.size)
        assertEquals(12, ThemeSeed.entries.map { it.key }.toSet().size)
        assertEquals(ThemeSeed.INDIGO, ThemeSeed.DEFAULT)
        assertEquals(ThemeSeed.DEFAULT, ThemeSeed.fromKey(null))
        assertEquals(ThemeSeed.DEFAULT, ThemeSeed.fromKey("no-such-color"))
        ThemeSeed.entries.forEach { assertSame(it, ThemeSeed.fromKey(it.key)) }
    }

    @Test
    fun textRolesAreReadable() {
        for (seed in ThemeSeed.entries) for (dark in listOf(false, true)) {
            val s = SeedSchemes.of(seed, dark)
            val tag = "$seed dark=$dark"
            assertTrue("$tag onPrimary", contrast(s.onPrimary, s.primary) >= 4.5)
            assertTrue("$tag onPrimaryContainer", contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5)
            assertTrue("$tag onSecondaryContainer", contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
            assertTrue("$tag onSurface", contrast(s.onSurface, s.surface) >= 7.0)
            assertTrue("$tag onSurfaceVariant", contrast(s.onSurfaceVariant, s.surfaceContainerHigh) >= 4.5)
            assertTrue("$tag onError", contrast(s.onError, s.error) >= 4.5)
        }
    }

    @Test
    fun lightAndDarkSchemesHaveExpectedTones() {
        for (seed in ThemeSeed.entries) {
            val light = SeedSchemes.of(seed, false)
            val dark = SeedSchemes.of(seed, true)
            assertTrue(seed.name, luminance(light.surface) > 0.8)
            assertTrue(seed.name, luminance(dark.surface) < 0.05)
            assertTrue(seed.name, luminance(dark.primary) > luminance(light.primary))
            // Surface containers step monotonically away from the background.
            assertTrue(seed.name, luminance(light.surfaceContainerLowest) >= luminance(light.surfaceContainerHighest))
            assertTrue(seed.name, luminance(dark.surfaceContainerLowest) <= luminance(dark.surfaceContainerHighest))
        }
    }

    @Test
    fun primaryKeepsTheSeedHue() {
        for (seed in ThemeSeed.entries.filterNot { it.neutral }) {
            val seedHue = Hct.fromInt(seed.argb).hue
            val primaryHue = Hct.fromInt(SeedSchemes.of(seed, false).primary).hue
            val d = abs(seedHue - primaryHue).let { min(it, 360 - it) }
            assertTrue("$seed hue $seedHue vs $primaryHue", d < 15.0)
        }
    }

    @Test
    fun neutralSeedIsLowChroma() {
        val taupe = Hct.fromInt(SeedSchemes.of(ThemeSeed.TAUPE, false).primary).chroma
        val indigo = Hct.fromInt(SeedSchemes.of(ThemeSeed.INDIGO, false).primary).chroma
        assertTrue("taupe=$taupe indigo=$indigo", taupe < 20 && indigo > taupe * 1.5)
    }

    @Test
    fun palettesAreDistinct() {
        val primaries = ThemeSeed.entries.map { SeedSchemes.of(it, false).primary }
        assertEquals(primaries.size, primaries.toSet().size)
        // Neighbouring presets should still differ visibly in hue (≥ 10°).
        val hues = ThemeSeed.entries.filterNot { it.neutral }.map { Hct.fromInt(SeedSchemes.of(it, false).primary).hue }
        for (i in hues.indices) for (j in i + 1 until hues.size) {
            val d = abs(hues[i] - hues[j]).let { min(it, 360 - it) }
            assertTrue("hue ${ThemeSeed.entries[i]} vs ${ThemeSeed.entries[j]}: $d", d >= 10.0)
        }
    }

    @Test
    fun generationIsCachedAndDeterministic() {
        val a = SeedSchemes.generate(0xFF3F51B5.toInt(), dark = false)
        assertSame(a, SeedSchemes.generate(0xFF3F51B5.toInt(), dark = false))
        assertNotEquals(a, SeedSchemes.generate(0xFF3F51B5.toInt(), dark = true))
        assertEquals(0xFF, a.primary ushr 24) // opaque
    }

    private fun luminance(argb: Int): Double {
        fun ch(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
