package com.nekohome.translate.data

import android.content.Intent
import com.nekohome.translate.engine.SegmentStats
import com.nekohome.translate.llama.GenerationStats
import com.nekohome.translate.llama.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long text from 选中文字 / 分享 must arrive, travel and be segmented without losing a character. */
class LongIncomingTextTest {

    /** ~[n] chars of mixed CJK / Latin / emoji (surrogate pairs) with paragraph and line breaks. */
    private fun longText(n: Int): String {
        val parts = listOf(
            "这是一段很长的中文文本，用来测试截断问题。", "Emoji 😀🐱‍👤 and 𠮷 (surrogate pairs) here! ",
            "第二句没有句号但是有逗号，继续写下去", "\n", "A plain English sentence. ", "\n\n", "😺😺😺",
        )
        val sb = StringBuilder()
        var i = 0
        while (sb.length < n) { sb.append(parts[i % parts.size]); i++ }
        return "START|" + sb.toString() + "|END"
    }

    private fun noLoneSurrogates(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2; continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    private fun stripWs(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun twentyThousandCharsArriveIntact() {
        val text = longText(20_000)
        assertTrue(text.length in 20_000..20_100)
        // PROCESS_TEXT arrives as a CharSequence (often a Spanned): use a non-String one.
        val r = IncomingText.quickRequest(Intent.ACTION_PROCESS_TEXT, null, StringBuilder(text), null, readOnly = false, forResult = true)!!
        assertEquals(text, r.text)
        assertFalse(r.truncated)
        assertEquals(text.length, r.originalLength)
        val shared = IncomingText.quickRequest(Intent.ACTION_SEND, "text/plain", null, text, readOnly = false, forResult = false)!!
        assertEquals(text, shared.text)
        assertEquals(text, IncomingText.clean(text))
    }

    @Test
    fun overTheCapIsCutVisiblyAndNeverInsideASurrogatePair() {
        // Put an emoji exactly across the cap boundary.
        val head = "a".repeat(IncomingText.MAX_CHARS - 1)
        val text = head + "😀" + "tail".repeat(100)
        val c = IncomingText.cleanDetailed(text)!!
        assertTrue(c.truncated)
        assertEquals(text.length, c.originalLength)
        assertEquals(head, c.text)
        assertTrue(noLoneSurrogates(c.text))
        val r = IncomingText.quickRequest(Intent.ACTION_PROCESS_TEXT, null, text, null, false, true)!!
        assertTrue(r.truncated)
        assertEquals(text.length, r.originalLength)
    }

    @Test
    fun handoffCarriesTheFullTextOnceAndKeepsBigPayloadsOutOfTheIntent() {
        val src = longText(20_000)
        val tr = longText(30_000)
        val p = HandoffPayload(src, tr, "en", src.length)
        val id = TextHandoff.put(p)
        assertFalse(TextHandoff.inlineFits(p)) // 50k chars ≈ 100 KB in a Parcel: id only
        val got = TextHandoff.take(id)
        assertNotNull(got)
        assertEquals(src, got!!.source)
        assertEquals(tr, got.translation)
        assertNull(TextHandoff.take(id)) // handed over once
        assertNull(TextHandoff.take(0))
        assertTrue(TextHandoff.inlineFits(HandoffPayload("short", null, null)))
    }

    @Test
    fun handoffDropsOnlyTheOldestBeyondCapacity() {
        val ids = (1..6).map { TextHandoff.put(HandoffPayload("t$it", null, null)) }
        assertNull(TextHandoff.take(ids[0]))
        assertNull(TextHandoff.take(ids[1]))
        assertEquals("t6", TextHandoff.take(ids[5])!!.source)
        assertEquals("t3", TextHandoff.take(ids[2])!!.source)
    }

    @Test
    fun segmentsCoverTheWholeTextWithoutBreakingSurrogates() {
        val text = longText(20_000)
        for (limit in listOf(TextSegmenter.MIN_LIMIT, 333, TextSegmenter.MAX_LIMIT)) {
            val segs = TextSegmenter.split(text, limit)
            assertTrue(segs.size > 1)
            segs.forEach {
                assertTrue("segment over limit $limit", it.text.length <= limit)
                assertTrue("lone surrogate at limit $limit", noLoneSurrogates(it.text))
            }
            val joined = segs.joinToString("") { it.separator + it.text }
            assertEquals(stripWs(text), stripWs(joined))
            assertTrue(joined.startsWith("START|") && joined.endsWith("|END"))
        }
    }

    @Test
    fun hardSplitOfAnUnbrokenEmojiRunKeepsPairs() {
        val text = "😀".repeat(5_000) // 10 000 UTF-16 units, no spaces or punctuation
        for (limit in listOf(121, 200, 799)) {
            val segs = TextSegmenter.split(text, limit)
            segs.forEach { assertTrue(noLoneSurrogates(it.text)) }
            assertEquals(text, segs.joinToString("") { it.separator + it.text })
        }
    }

    @Test
    fun aStopStringEndsOnlyItsSegment() {
        val cancelled = GenerationStats(1, 10, 1.0, 5, 1.0, StopReason.CANCELLED)
        val eog = SegmentStats.afterGeneration(cancelled, stopStringHit = true, userCancelled = false)
        assertEquals(StopReason.EOG, eog.stopReason)
        assertTrue(eog.ok)
        // The user pressed 停止: stays cancelled even if the last piece also held a stop string.
        assertEquals(StopReason.CANCELLED, SegmentStats.afterGeneration(cancelled, true, userCancelled = true).stopReason)
        assertEquals(StopReason.CANCELLED, SegmentStats.afterGeneration(cancelled, false, false).stopReason)
        val maxTok = GenerationStats(0, 10, 1.0, 5, 1.0, StopReason.MAX_TOKENS)
        assertEquals(maxTok, SegmentStats.afterGeneration(maxTok, true, false))
        // Combined stats of a long text whose segments all ended in a stop string are savable.
        val combined = SegmentStats.combine(listOf(eog, eog, eog))
        assertTrue(HistoryRules.isSavable(combined.stopReason, "src", "dst"))
    }

    @Test
    fun replacementReturnsTheWholeLongTranslation() {
        val tr = longText(20_000)
        assertEquals(tr.trim(), IncomingText.replacement("\n$tr  "))
    }
}
