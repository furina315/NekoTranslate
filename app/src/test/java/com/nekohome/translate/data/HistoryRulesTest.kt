package com.nekohome.translate.data

import com.nekohome.translate.llama.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun entry(id: Long, at: ZonedDateTime, src: String = "你好", dst: String = "Hello") =
        HistoryEntry(id, src, dst, "zh", "en", "model", at.toInstant().toEpochMilli())

    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone)

    @Test
    fun onlyFinishedTranslationsAreSaved() {
        assertTrue(HistoryRules.isSavable(StopReason.EOG, "a", "b"))
        assertTrue(HistoryRules.isSavable(StopReason.MAX_TOKENS, "a", "b"))
        assertTrue(HistoryRules.isSavable(StopReason.CONTEXT_FULL, "a", "b"))
        assertFalse(HistoryRules.isSavable(StopReason.CANCELLED, "a", "b"))
        assertFalse(HistoryRules.isSavable(StopReason.ERROR, "a", "b"))
        assertFalse(HistoryRules.isSavable(StopReason.EOG, "  ", "b"))
        assertFalse(HistoryRules.isSavable(StopReason.EOG, "a", ""))
    }

    @Test
    fun duplicateNeedsSameTextsAndDirection() {
        val a = entry(1, at(27, 9))
        assertTrue(HistoryRules.isDuplicate(a, entry(0, at(27, 10))))
        assertFalse(HistoryRules.isDuplicate(null, a))
        assertFalse(HistoryRules.isDuplicate(a, a.copy(targetText = "Hi")))
        assertFalse(HistoryRules.isDuplicate(a, a.copy(targetCode = "ja")))
        assertFalse(HistoryRules.isDuplicate(a, a.copy(sourceCode = "auto")))
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertEquals("%hello%", HistoryRules.likePattern("  hello "))
        assertEquals("%100\\%%", HistoryRules.likePattern("100%"))
        assertEquals("%a\\_b%", HistoryRules.likePattern("a_b"))
        assertEquals("%c:\\\\dir%", HistoryRules.likePattern("c:\\dir"))
    }

    @Test
    fun dayOffsetCountsCalendarDaysNotHours() {
        val now = at(27, 0, 10).toInstant().toEpochMilli()
        assertEquals(0, HistoryRules.dayOffset(at(27, 0, 1).toInstant().toEpochMilli(), now, zone))
        // 23:50 the evening before is yesterday even though only 20 minutes ago.
        assertEquals(1, HistoryRules.dayOffset(at(26, 23, 50).toInstant().toEpochMilli(), now, zone))
        assertEquals(5, HistoryRules.dayOffset(at(22, 12).toInstant().toEpochMilli(), now, zone))
    }

    @Test
    fun groupsConsecutiveEntriesByLocalDay() {
        val list = listOf(entry(4, at(27, 9)), entry(3, at(27, 1)), entry(2, at(26, 23)), entry(1, at(20, 8)))
        val groups = HistoryRules.groupByDay(list, zone)
        assertEquals(3, groups.size)
        assertEquals(listOf(4L, 3L), groups[0].second.map { it.id })
        assertEquals(listOf(2L), groups[1].second.map { it.id })
        assertEquals(listOf(1L), groups[2].second.map { it.id })
        assertTrue(HistoryRules.groupByDay(emptyList(), zone).isEmpty())
    }
}
