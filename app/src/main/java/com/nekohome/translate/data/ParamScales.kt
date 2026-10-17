package com.nekohome.translate.data

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Value grids for the parameter controls on the 模型 page. Sliders are continuous (no tick
 * marks) and snap to these grids; the −/+ buttons step along the same grid. Pure functions so
 * they are unit-tested on the JVM.
 */
object ParamScales {

    /** Linear grid: [min]..[max] in steps of [step]. */
    data class Linear(val min: Float, val max: Float, val step: Float) {
        /** Nearest grid value to [v], clamped to the range, with float noise removed. */
        fun snap(v: Float): Float {
            val n = ((v.coerceIn(min, max) - min) / step).roundToInt()
            return clean((min + n * step).coerceIn(min, max))
        }

        /** Next grid value in direction [delta] (±1) from [current], which may be off-grid. */
        fun nudge(current: Float, delta: Int): Float {
            val snapped = snap(current)
            val next = when {
                // Off-grid value: the first move only goes to the neighbouring grid point.
                delta > 0 && snapped > current + EPS -> snapped
                delta < 0 && snapped < current - EPS -> snapped
                else -> snapped + delta * step
            }
            return clean(next.coerceIn(min, max))
        }

        fun canNudge(current: Float, delta: Int): Boolean =
            if (delta > 0) current < max - EPS else current > min + EPS
    }

    val TEMPERATURE = Linear(0f, 2f, 0.05f)
    val TOP_P = Linear(0f, 1f, 0.05f)
    val REPEAT_PENALTY = Linear(1f, 1.5f, 0.01f)

    /** Top-k stops: fine at the low end where it matters, coarse above. 0 = off. */
    val TOP_K_STOPS = listOf(0, 1, 5, 10, 20, 30, 40, 50, 64, 80, 100)

    val MAX_TOKEN_OPTIONS = listOf(256, 512, 1024, 2048, 4096, 8192)
    val CONTEXT_OPTIONS = listOf(1024, 2048, 4096, 8192, 16384, 32768)

    /** Index of the option closest to [value] (ties go to the smaller option). */
    fun nearestIndex(options: List<Int>, value: Int): Int =
        options.indices.minByOrNull { abs(options[it].toLong() - value) } ?: 0

    /** Option picked by a continuous slider position over option indices. */
    fun optionAt(options: List<Int>, position: Float): Int =
        options[position.roundToInt().coerceIn(0, options.lastIndex)]

    /**
     * Next option strictly above ([delta] > 0) or below ([delta] < 0) [current]; [current] itself
     * need not be an option. Returns [current] when already at the end.
     */
    fun stepOption(options: List<Int>, current: Int, delta: Int): Int =
        if (delta > 0) options.firstOrNull { it > current } ?: current
        else options.lastOrNull { it < current } ?: current

    /** Context options a model supports: no larger than its training context (if known). */
    fun contextOptions(trainCtx: Long?): List<Int> =
        CONTEXT_OPTIONS.filter { trainCtx == null || it <= trainCtx }.ifEmpty { CONTEXT_OPTIONS.take(1) }

    /** "512", "1K", "32K" — compact token-count labels for segmented buttons. */
    fun tokenLabel(n: Int): String = if (n >= 1024 && n % 1024 == 0) "${n / 1024}K" else n.toString()

    private const val EPS = 1e-4f

    private fun clean(v: Float): Float = ((v * 100.0).roundToLong() / 100.0).toFloat()
}
