package com.nekohome.translate.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel size of a decoded image. */
data class ImageSize(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val pixels: Long get() = width.toLong() * height
}

/**
 * Pure sizing rules for images sent to the vision encoder (no Android types, unit-tested).
 *
 * Index-Translate recommends a longest edge of 512–2048 px. The vision tower (Qwen3-VL style,
 * patch 16, 2x2 merge) produces one token per 32x32 px, so the pixel budget also follows from
 * the token budget that fits in the context next to the prompt and the generated translation.
 *
 * Phone CPUs are the limit: the encoder's attention is quadratic in patches (4 per token), so a
 * 1536 px photo (~1700 tokens) took minutes. 1024 px / 1024 tokens keeps signs, menus and
 * screenshots legible at roughly a quarter of the encode cost.
 */
object ImagePrep {

    /** Longest edge we decode to; larger photos are downscaled. */
    const val MAX_EDGE = 1024

    /** Below this longest edge we never shrink further for the pixel budget (unless the source is smaller). */
    const val MIN_EDGE = 512

    /** Pixels per image token for a patch-16, 2x2-merge vision encoder. */
    const val PIXELS_PER_TOKEN = 32 * 32

    /**
     * Upper bound on image tokens, whatever the context (memory and encode time on a phone);
     * passed to mtmd as image_max_tokens. MAX_EDGE² pixels fit it exactly (1024 x 1024 = 1024 tokens).
     */
    const val MAX_IMAGE_TOKENS = 1024

    private const val MIN_IMAGE_TOKENS = 256
    private const val PROMPT_RESERVE = 256

    /**
     * Image tokens we allow for a context of [nCtx] tokens when up to [maxGenTokens] may be
     * generated: what is left after the generation and a small prompt reserve, clamped to
     * [MIN_IMAGE_TOKENS]..[MAX_IMAGE_TOKENS].
     */
    fun imageTokenBudget(nCtx: Int, maxGenTokens: Int): Int {
        val gen = if (maxGenTokens > 0) min(maxGenTokens, nCtx / 2) else nCtx / 4
        return (nCtx - gen - PROMPT_RESERVE).coerceIn(MIN_IMAGE_TOKENS, MAX_IMAGE_TOKENS)
    }

    /**
     * Size to decode a [src] image to: longest edge at most [maxEdge] and at most [maxPixels]
     * pixels, aspect ratio kept, never upscaled. The pixel cap does not push the longest edge
     * below [MIN_EDGE] (mtmd still enforces its own token limit by resizing).
     */
    fun targetSize(src: ImageSize, maxEdge: Int = MAX_EDGE, maxPixels: Long = Long.MAX_VALUE): ImageSize {
        require(src.width > 0 && src.height > 0) { "Empty image" }
        var scale = min(1.0, maxEdge.toDouble() / src.longEdge)
        val pixelsAtScale = src.pixels * scale * scale
        if (pixelsAtScale > maxPixels) {
            val byPixels = scale * sqrt(maxPixels / pixelsAtScale)
            val floor = min(scale, MIN_EDGE.toDouble() / src.longEdge)
            scale = max(byPixels, floor)
        }
        if (scale >= 1.0) return src
        return ImageSize(
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
        )
    }

    /**
     * Largest power-of-two BitmapFactory `inSampleSize` that still decodes [src] at least as large
     * as [target] on both edges (the final, exact resize happens after decoding).
     */
    fun sampleSize(src: ImageSize, target: ImageSize): Int {
        var s = 1
        while (src.width / (s * 2) >= target.width && src.height / (s * 2) >= target.height) s *= 2
        return s
    }

    /** Width/height after EXIF rotation by [degrees] (90 / 270 swap them). */
    fun rotated(size: ImageSize, degrees: Int): ImageSize =
        if (((degrees % 360) + 360) % 360 == 90 || ((degrees % 360) + 360) % 360 == 270) {
            ImageSize(size.height, size.width)
        } else {
            size
        }

    /** Thumbnail size: longest edge at most [maxEdge], never upscaled. */
    fun thumbnailSize(src: ImageSize, maxEdge: Int = 384): ImageSize = targetSize(src, maxEdge)

    /**
     * Packs non-premultiplied ARGB pixels (as returned by Bitmap.getPixels) into RGB888 bytes at
     * [offset]. Translucent pixels are composited over white, so dark text on a transparent PNG
     * stays readable instead of turning into black on black.
     */
    fun argbToRgb(argb: IntArray, count: Int, out: ByteArray, offset: Int) {
        var o = offset
        for (i in 0 until count) {
            val c = argb[i]
            val a = c ushr 24
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            if (a != 0xFF) {
                val inv = 255 - a
                r = (r * a + 255 * inv + 127) / 255
                g = (g * a + 255 * inv + 127) / 255
                b = (b * a + 255 * inv + 127) / 255
            }
            out[o] = r.toByte()
            out[o + 1] = g.toByte()
            out[o + 2] = b.toByte()
            o += 3
        }
    }
}
