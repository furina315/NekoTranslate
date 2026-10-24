package com.nekohome.translate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrepTest {

    @Test
    fun largePhotoIsCappedAtMaxEdgeKeepingAspect() {
        val t = ImagePrep.targetSize(ImageSize(4000, 3000))
        assertEquals(ImageSize(1024, 768), t)
        val portrait = ImagePrep.targetSize(ImageSize(3024, 4032))
        assertEquals(1024, portrait.height)
        assertEquals(768, portrait.width)
    }

    @Test
    fun smallImagesAreNeverUpscaled() {
        assertEquals(ImageSize(800, 600), ImagePrep.targetSize(ImageSize(800, 600)))
        assertEquals(ImageSize(300, 200), ImagePrep.targetSize(ImageSize(300, 200), maxPixels = 10_000))
    }

    @Test
    fun pixelBudgetShrinksButNotBelowMinEdge() {
        // 1024x1024 = 1 MP over a 0.5 MP budget -> about 724x724.
        val t = ImagePrep.targetSize(ImageSize(3000, 3000), maxPixels = 512L * 1024)
        assertTrue(t.pixels <= 512L * 1024 + 2048)
        assertEquals(t.width, t.height)
        assertTrue(t.width in 720..725)
        // A tiny budget stops at a 512 px longest edge.
        val floor = ImagePrep.targetSize(ImageSize(4000, 2000), maxPixels = 10_000)
        assertEquals(ImageSize(512, 256), floor)
    }

    @Test
    fun tokenBudgetFollowsContext() {
        assertEquals(1024, ImagePrep.imageTokenBudget(nCtx = 4096, maxGenTokens = 1024))
        assertEquals(1024, ImagePrep.imageTokenBudget(nCtx = 16384, maxGenTokens = 1024))
        // 2048 ctx: generation capped at half the context -> 2048 - 1024 - 256.
        assertEquals(768, ImagePrep.imageTokenBudget(nCtx = 2048, maxGenTokens = 1024))
        assertEquals(256, ImagePrep.imageTokenBudget(nCtx = 512, maxGenTokens = 1024))
        // Default target (4096 ctx, 1024 tokens): a 4:3 photo becomes 1024x768 = 32 x 24 = 768 tokens.
        val budget = ImagePrep.imageTokenBudget(nCtx = 4096, maxGenTokens = 1024)
        val t = ImagePrep.targetSize(ImageSize(4000, 3000), maxPixels = budget.toLong() * ImagePrep.PIXELS_PER_TOKEN)
        assertEquals(ImageSize(1024, 768), t)
        // The largest image MAX_EDGE allows always fits the token cap.
        assertTrue(ImagePrep.MAX_EDGE.toLong() * ImagePrep.MAX_EDGE <= ImagePrep.MAX_IMAGE_TOKENS.toLong() * ImagePrep.PIXELS_PER_TOKEN)
    }

    @Test
    fun imageTokensPlusGenerationFitTheContext() {
        // Image budget + prompt reserve + generation never exceeds the context for sane sizes.
        for (nCtx in listOf(1024, 2048, 4096, 8192, 32768)) {
            for (gen in listOf(256, 512, 1024, 2048)) {
                val img = ImagePrep.imageTokenBudget(nCtx, gen)
                val effGen = minOf(gen, nCtx / 2)
                assertTrue("ctx=$nCtx gen=$gen img=$img", img + effGen + 256 <= nCtx)
                assertTrue(img <= ImagePrep.MAX_IMAGE_TOKENS)
            }
        }
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
