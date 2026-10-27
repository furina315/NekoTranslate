package com.nekohome.translate.data

import androidx.compose.runtime.Immutable
import com.nekohome.translate.llama.StopReason
import java.time.Instant
import java.time.ZoneId

/** One saved translation. [sourceCode] may be "auto" (language was auto-detected). */
@Immutable
data class HistoryEntry(
    val id: Long,
    val sourceText: String,
    val targetText: String,
    val sourceCode: String,
    val targetCode: String,
    val modelName: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

/** Pure rules behind 翻译历史, kept free of Android so they can be unit-tested on the JVM. */
object HistoryRules {
    /** Older entries beyond this many are dropped when a new one is added. */
    const val MAX_ENTRIES = 1000

    /** Max rows one search returns (the list is not paged). */
    const val SEARCH_LIMIT = 500

    /** Only a translation that ran to its natural end (or was cut by a limit) is worth keeping. */
    fun isSavable(stop: StopReason, sourceText: String, targetText: String): Boolean =
        sourceText.isNotBlank() && targetText.isNotBlank() &&
            (stop == StopReason.EOG || stop == StopReason.MAX_TOKENS || stop == StopReason.CONTEXT_FULL)

    /** The same text pair in the same direction as [latest]: refresh its time instead of adding a row. */
    fun isDuplicate(latest: HistoryEntry?, new: HistoryEntry): Boolean =
        latest != null &&
            latest.sourceText == new.sourceText && latest.targetText == new.targetText &&
            latest.sourceCode == new.sourceCode && latest.targetCode == new.targetCode

    /** `LIKE` pattern (escape character `\`) matching [query] anywhere; `%` `_` `\` are literal. */
    fun likePattern(query: String): String {
        val sb = StringBuilder("%")
        for (c in query.trim()) {
            if (c == '%' || c == '_' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.append('%').toString()
    }

    /** Whole calendar days from [timeMs] to [nowMs] in [zone]: 0 = today, 1 = yesterday. */
    fun dayOffset(timeMs: Long, nowMs: Long, zone: ZoneId): Long {
        val day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return today.toEpochDay() - day.toEpochDay()
    }

    /** Groups consecutive entries (newest first) that fall on the same local day. */
    fun groupByDay(entries: List<HistoryEntry>, zone: ZoneId): List<Pair<java.time.LocalDate, List<HistoryEntry>>> {
        val out = ArrayList<Pair<java.time.LocalDate, MutableList<HistoryEntry>>>()
        for (e in entries) {
            val day = Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDate()
            val last = out.lastOrNull()
            if (last != null && last.first == day) last.second.add(e) else out.add(day to mutableListOf(e))
        }
        return out
    }
}
