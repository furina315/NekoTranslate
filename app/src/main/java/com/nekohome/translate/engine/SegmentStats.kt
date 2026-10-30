package com.nekohome.translate.engine

import com.nekohome.translate.llama.GenerationStats
import com.nekohome.translate.llama.StopReason

object SegmentStats {
    /** Sums per-segment stats; status / stop reason come from the first failed segment, else the last. */
    fun combine(all: List<GenerationStats>): GenerationStats {
        if (all.isEmpty()) return GenerationStats(0, 0, 0.0, 0, 0.0, StopReason.EOG)
        val bad = all.firstOrNull { !it.ok || it.stopReason == StopReason.CANCELLED || it.stopReason == StopReason.ERROR }
        val pick = bad ?: all.last()
        return GenerationStats(
            status = pick.status,
            promptTokens = all.sumOf { it.promptTokens },
            promptMs = all.sumOf { it.promptMs },
            generatedTokens = all.sumOf { it.generatedTokens },
            generationMs = all.sumOf { it.generationMs },
            stopReason = pick.stopReason,
        )
    }
}
