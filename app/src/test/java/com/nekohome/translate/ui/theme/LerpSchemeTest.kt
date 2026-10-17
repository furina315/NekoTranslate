package com.nekohome.translate.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LerpSchemeTest {
    private val a = lightColorScheme(primary = Color(0xFF000000), surface = Color(0xFFFFFFFF))
    private val b = darkColorScheme(primary = Color(0xFFFFFFFF), surface = Color(0xFF000000))

    @Test
    fun endpointsReturnTheSameInstances() {
        assertSame(a, lerpScheme(a, b, 0f))
        assertSame(b, lerpScheme(a, b, 1f))
        assertSame(b, lerpScheme(b, b, 0.5f))
    }

    @Test
    fun midpointBlendsEveryRole() {
        // Compose's Color.lerp blends in Oklab, so the midpoint is perceptual, not 0.5 in sRGB.
        val mid = lerpScheme(a, b, 0.5f)
        assertTrue(mid.primary.red in 0.1f..0.9f)
        assertTrue(mid.surface.red in 0.1f..0.9f)
        assertEquals(mid.primary.red, mid.surface.red, 0.01f) // black→white and white→black meet at the same grey
        assertEquals(1f, mid.primary.alpha, 0.001f)
    }
}
