package com.nekohome.translate.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class GlossaryAndSegmentTest {
    private val index = BuiltInProfiles.INDEX_TRANSLATE
    private val zh = index.findLanguage("zh")!!
    private val en = index.findLanguage("en")!!

    @Test fun constraintPromptMatchesOfficialFormat() {
        val p = index.buildUserPrompt(en, zh, "Neko met Shio.", listOf(GlossaryEntry("Neko", "猫猫"), GlossaryEntry("Shio", "小汐")))
        assertEquals(
            "请将以下英语文本翻译成中文，并且严格遵循所有约束要求。\n\n【源文】\nNeko met Shio.\n\n" +
                "【约束要求】\n1. 【硬性要求】专名/术语对照: Neko→猫猫、Shio→小汐\n\n只输出译文，不要有任何额外说明。",
            p,
        )
    }

    @Test fun constraintPromptAutoSourceOmitsSourceName() {
        val p = index.buildUserPrompt(Language.AUTO, zh, "x", listOf(GlossaryEntry("x", "y")))
        assertTrue(p.startsWith("请将以下文本翻译成中文，并且严格遵循所有约束要求。"))
    }

    @Test fun noTermsKeepsPlainPrompt() {
        assertEquals(
            "请将以下英语文本翻译为中文，直接输出翻译结果，不要进行任何解释。\n\nhello",
            index.buildUserPrompt(en, zh, "hello"),
        )
    }

    @Test fun otherModelsGetHintSuffix() {
        val hy = BuiltInProfiles.HY_MT2
        val p = hy.buildUserPrompt(hy.findLanguage("en")!!, hy.findLanguage("zh")!!, "hello", listOf(GlossaryEntry("hello", "你好")))
        assertTrue(p.endsWith("\n\n请严格使用以下术语译法：hello → 你好"))
        assertTrue("【源文】" !in p)
    }

    @Test fun matchIsCaseInsensitiveLongestFirstAndDeduped() {
        val entries = listOf(
            GlossaryEntry("New York", "纽约"), GlossaryEntry("York", "约克"),
            GlossaryEntry("new york", "重复"), GlossaryEntry("Paris", "巴黎"),
        )
        val m = GlossaryRules.match("I love NEW YORK and York Minster", entries)
        assertEquals(listOf("New York", "York"), m.map { it.source })
        assertEquals(listOf("New York"), GlossaryRules.match("new york", entries).map { it.source })
    }

    @Test fun matchTruncates() {
        val entries = (1..50).map { GlossaryEntry("w$it", "t$it") }
        val text = (1..50).joinToString(" ") { "w$it" }
        assertEquals(GlossaryRules.MAX_TERMS_PER_PROMPT, GlossaryRules.match(text, entries).size)
        assertEquals(2, GlossaryRules.match(text, entries, maxChars = 10).size)
    }

    @Test fun serializeRoundTrip() {
        val l = listOf(GlossaryEntry("a\tb", "c\\d"), GlossaryEntry("x\ny", "z"))
        assertEquals(l, GlossaryRules.parse(GlossaryRules.serialize(l)))
        assertEquals(emptyList<GlossaryEntry>(), GlossaryRules.parse(null))
    }

    @Test fun shortTextIsOneSegment() {
        assertEquals(listOf(Segment("你好。世界。", "")), TextSegmenter.split("你好。世界。", 100))
    }

    @Test fun longTextSplitsOnParagraphsAndSentences() {
        val text = "第一句。第二句。\n\n第三句很长很长。第四句。"
        val segs = TextSegmenter.split(text, 10)
        assertTrue(segs.size > 1)
        assertTrue(segs.all { it.text.length <= 10 })
        val joined = segs.joinToString("") { it.separator + it.text }
        assertEquals(text, joined)
        assertTrue(segs.any { it.separator == "\n\n" })
    }

    @Test fun englishSegmentsKeepSpaces() {
        val text = "One two three. Four five six. Seven eight nine."
        val segs = TextSegmenter.split(text, 20)
        assertEquals(text, segs.joinToString("") { it.separator + it.text })
        assertTrue(segs.all { it.text.length <= 20 })
    }

    @Test fun hugeSentenceIsHardSplit() {
        val text = "a".repeat(250)
        val segs = TextSegmenter.split(text, 100)
        assertEquals(3, segs.size)
        assertTrue(segs.all { it.text.length <= 100 })
        assertEquals(text, segs.joinToString("") { it.text })
    }

    @Test fun limitScalesWithContext() {
        assertEquals(TextSegmenter.MIN_LIMIT, TextSegmenter.segmentLimit(256, 1024))
        assertTrue(TextSegmenter.segmentLimit(8192, 4096) > TextSegmenter.segmentLimit(2048, 4096))
        assertTrue(TextSegmenter.segmentLimit(32768, 8192) <= TextSegmenter.MAX_LIMIT)
        assertTrue(TextSegmenter.segmentLimit(2048, 4096, 680) < TextSegmenter.segmentLimit(2048, 4096, 0))
    }

    @Test fun plannerAttachesOnlyPerSegmentTerms() {
        val a = "Alice went home. ".repeat(20)
        val b = "Bob stayed. ".repeat(20)
        val plan = TranslationPlanner.plan(
            index, en, zh, a + "\n\n" + b, nCtx = 512, maxTokens = 256,
            glossary = listOf(GlossaryEntry("Alice", "爱丽丝"), GlossaryEntry("Bob", "鲍勃")),
        )
        assertTrue(plan.size >= 2)
        assertTrue("Alice→爱丽丝" in plan.first().prompt && "Bob→鲍勃" !in plan.first().prompt)
        assertTrue("Bob→鲍勃" in plan.last().prompt && "Alice→爱丽丝" !in plan.last().prompt)
    }
}
