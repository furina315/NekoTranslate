package com.nekohome.translate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrepTest {

    @Test
    fun largePhotoIsCappedAtMaxEdgeKeepingAspect() {
        val t = ImagePrep.targetSize(ImageSize(4000, 3000))
        assertEquals(ImageSize(1536, 1152), t)
        val portrait = ImagePrep.targetSize(ImageSize(3024, 4032))
        assertEquals(1536, portrait.height)
        assertEquals(1152, portrait.width)
    }

    @Test
    fun smallImagesAreNeverUpscaled() {
        assertEquals(ImageSize(800, 600), ImagePrep.targetSize(ImageSize(800, 600)))
        assertEquals(ImageSize(300, 200), ImagePrep.targetSize(ImageSize(300, 200), maxPixels = 10_000))
    }

    @Test
    fun pixelBudgetShrinksButNotBelowMinEdge() {
        // 1536x1536 = 2.36 MP over a 1 MP budget -> about 1024x1024.
        val t = ImagePrep.targetSize(ImageSize(3000, 3000), maxPixels = 1024L * 1024)
        assertTrue(t.pixels <= 1024L * 1024 + 2048)
        assertEquals(t.width, t.height)
        assertTrue(t.width in 1020..1024)
        // A tiny budget stops at a 512 px longest edge.
        val floor = ImagePrep.targetSize(ImageSize(4000, 2000), maxPixels = 10_000)
        assertEquals(ImageSize(512, 256), floor)
    }

    @Test
    fun tokenBudgetFollowsContext() {
        assertEquals(2048, ImagePrep.imageTokenBudget(nCtx = 4096, maxGenTokens = 1024))
        assertEquals(2048, ImagePrep.imageTokenBudget(nCtx = 16384, maxGenTokens = 1024))
        // 2048 ctx: generation capped at half the context -> 2048 - 1024 - 256.
        assertEquals(768, ImagePrep.imageTokenBudget(nCtx = 2048, maxGenTokens = 1024))
        assertEquals(256, ImagePrep.imageTokenBudget(nCtx = 512, maxGenTokens = 1024))
        // Default target (4096 ctx, 1024 tokens) fits a 1536x1152 photo: 48 x 36 = 1728 tokens.
        val t = ImagePrep.targetSize(ImageSize(4000, 3000), maxPixels = 2048L * ImagePrep.PIXELS_PER_TOKEN)
        assertEquals(ImageSize(1536, 1152), t)
    }

    @Test
    fun sampleSizeIsLargestPowerOfTwoNotBelowTarget() {
        assertEquals(1, ImagePrep.sampleSize(ImageSize(1000, 800), ImageSize(1000, 800)))
        assertEquals(2, ImagePrep.sampleSize(ImageSize(4000, 3000), ImageSize(1536, 1152)))
        assertEquals(4, ImagePrep.sampleSize(ImageSize(8000, 6000), ImageSize(1536, 1152)))
        assertEquals(1, ImagePrep.sampleSize(ImageSize(3000, 2000), ImageSize(1536, 1024)))
    }

    @Test
    fun rotationSwapsEdgesFor90And270() {
        assertEquals(ImageSize(3000, 4000), ImagePrep.rotated(ImageSize(4000, 3000), 90))
        assertEquals(ImageSize(3000, 4000), ImagePrep.rotated(ImageSize(4000, 3000), 270))
        assertEquals(ImageSize(4000, 3000), ImagePrep.rotated(ImageSize(4000, 3000), 180))
        assertEquals(ImageSize(3000, 4000), ImagePrep.rotated(ImageSize(4000, 3000), -90))
    }

    @Test
    fun argbIsPackedToRgbAndAlphaBlendedOverWhite() {
        val px = intArrayOf(0xFF112233.toInt(), 0x00000000, 0x80000000.toInt())
        val out = ByteArray(9)
        ImagePrep.argbToRgb(px, 3, out, 0)
        val u = out.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x11, 0x22, 0x33), u.subList(0, 3))
        assertEquals(listOf(255, 255, 255), u.subList(3, 6)) // fully transparent -> white
        assertEquals(listOf(127, 127, 127), u.subList(6, 9)) // 50% black over white
    }
}
