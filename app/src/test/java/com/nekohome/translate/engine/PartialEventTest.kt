package com.nekohome.translate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PartialEventTest {

    @Test
    fun prefixAndTailJoinLazily() {
        val p = GenerationEvent.Partial("第一段译文\n\n", "第二段", 2, 3)
        assertEquals("第一段译文\n\n第二段", p.text)
        assertEquals(2, p.segment)
        assertEquals(3, p.segmentCount)
    }

    @Test
    fun singleTextConstructorAndEquality() {
        assertEquals(GenerationEvent.Partial("abc"), GenerationEvent.Partial("a", "bc", 1, 1))
        assertEquals(GenerationEvent.Partial("abc").hashCode(), GenerationEvent.Partial("ab", "c").hashCode())
        assertFalse(GenerationEvent.Partial("abc", 1, 2) == GenerationEvent.Partial("abc", 2, 2))
    }

    @Test
    fun toStringDoesNotLeakText() {
        val s = GenerationEvent.Partial("机密内容").toString()
        assertFalse(s.contains("机密"))
        assertEquals("Partial(4 chars, segment=1/1)", s)
    }
}
