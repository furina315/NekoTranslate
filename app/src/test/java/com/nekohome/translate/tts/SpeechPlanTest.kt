package com.nekohome.translate.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlanTest {
    private fun parts(t: String, max: Int = 100) = SpeechPlan.split(t, max).map { t.substring(it.start, it.end) }

    @Test fun splitsChineseAndEnglish() {
        assertEquals(listOf("你好。", "今天天气不错！", "Hello world.", "Ok?"), parts("你好。今天天气不错！Hello world. Ok?"))
    }

    @Test fun keepsDecimalsAndNewlines() {
        assertEquals(listOf("pi is 3.14 ok.", "next"), parts("pi is 3.14 ok.\nnext"))
    }

    @Test fun spansMapBackToOriginal() {
        val t = "  甲。\n\n  乙乙。 "
        val s = SpeechPlan.split(t, 50)
        assertEquals(2, s.size)
        assertEquals("甲。", t.substring(s[0].start, s[0].end))
        assertEquals("乙乙。", t.substring(s[1].start, s[1].end))
        assertEquals(1, SpeechPlan.indexAt(s, t.indexOf('乙')))
        assertEquals(-1, SpeechPlan.indexAt(s, 0))
    }

    @Test fun longSentenceRespectsMaxLen() {
        val t = "一二三，四五六，七八九，十十十，" + "a".repeat(30)
        val s = SpeechPlan.split(t, 10)
        assertTrue(s.all { it.length <= 10 })
        assertEquals(t.filter { !it.isWhitespace() }, s.joinToString("") { t.substring(it.start, it.end) })
    }

    @Test fun blankGivesNothing() {
        assertTrue(SpeechPlan.split("  \n ", 10).isEmpty())
    }

    @Test fun closingQuoteStaysWithSentence() {
        assertEquals(listOf("他说：“好。”", "走吧。"), parts("他说：“好。”走吧。"))
    }

    @Test fun pitchSupport() {
        assertFalse(SpeechPlan.pitchSupported("com.k2fsa.sherpa.onnx.tts.engine"))
        assertTrue(SpeechPlan.pitchSupported("com.google.android.tts"))
        assertTrue(SpeechPlan.pitchSupported(null))
    }

    @Test fun wordSpanAndStops() {
        val s = SpeechSpan(10, 20)
        assertEquals(SpeechSpan(12, 15), SpeechPlan.wordSpan(s, 2, 5))
        assertNull(SpeechPlan.wordSpan(s, 2, 50))
        assertEquals(2, SpeechPlan.nearestIndex(SpeechPlan.RATE_STOPS, 1.05f))
    }
}
