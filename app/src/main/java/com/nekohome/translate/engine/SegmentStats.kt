package com.nekohome.translate.engine

import com.nekohome.translate.llama.GenerationStats
import com.nekohome.translate.llama.StopReason

object SegmentStats {
    /**
     * The token callback returns false both when the user stops and when the output reached one of
     * the profile's stop strings; natively both end as CANCELLED. A stop string is the model's own
     * end of answer, so it counts as EOG: otherwise a long text stopped after the first segment
     * that ended in a stop string (the rest silently untranslated) and was not saved to history.
     */
    fun afterGeneration(st: GenerationStats, stopStringHit: Boolean, userCancelled: Boolean): GenerationStats =
        if (stopStringHit && !userCancelled && st.stopReason == StopReason.CANCELLED) {
            st.copy(status = 0, stopReason = StopReason.EOG)
        } else {
            st
        }

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
