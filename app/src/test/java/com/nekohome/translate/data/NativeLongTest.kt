package com.nekohome.translate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLongTest {
    private val nl = BuiltInProfiles.INDEX_NATIVELONG
    private val zh = nl.findLanguage("zh")!!
    private val en = nl.findLanguage("en")!!
    private val ja = nl.findLanguage("ja")!!
    private val ko = nl.findLanguage("ko")!!

    /** bilibili/Index-Translate inference/llm/prompts/nailong_zh-en.txt, byte for byte. */
    private val officialZhEn = "请将以下中文小说翻译成英语，并且严格遵循所有约束要求。\n\n" +
        "这是一段长篇连续文本，请完整处理全部内容，并在全文范围内保持译名和表达一致。\n\n" +
        "【约束要求】\n\n" +
        "1. 完整翻译所有内容，不要遗漏、概括、合并或添加内容。\n" +
        "2. 保持人名、地名、称谓和术语在全文中一致。\n" +
        "3. 保持原文的章节、段落和内容顺序。\n\n" +
        "只输出译文，不要有任何额外说明。\n\n" +
        "【源文】\n\n" +
        "（在这里放入需要翻译的完整中文原文）\n"

    @Test fun templatesMatchOfficialFiles() {
        assertEquals(officialZhEn, NativeLongPrompts.template("zh-en"))
        assertTrue(NativeLongPrompts.template("en-zh")!!.startsWith("请将以下英文小说翻译成中文，"))
        assertTrue(NativeLongPrompts.template("en-zh")!!.endsWith("（在这里放入需要翻译的完整英文原文）\n"))
        assertTrue(NativeLongPrompts.template("zh-ja")!!.startsWith("请将以下中文小说翻译成日语，"))
        assertTrue(NativeLongPrompts.template("ja-zh")!!.endsWith("（在这里放入需要翻译的完整日文原文）\n"))
        assertNull(NativeLongPrompts.template("en-ja"))
    }

    @Test fun documentPromptReplacesPlaceholderLikeOfficialClient() {
        val p = nl.buildUserPrompt(zh, en, "  第一章\n\n小汐推开门。 ")
        assertEquals(officialZhEn.replace("（在这里放入需要翻译的完整中文原文）", "第一章\n\n小汐推开门。"), p)
    }

    @Test fun glossaryBecomesFourthHardConstraint() {
        val p = nl.buildUserPrompt(zh, en, "小汐和阿洛", listOf(GlossaryEntry("小汐", "Shio"), GlossaryEntry("阿洛", "Arlo")))
        assertTrue(p.contains("3. 保持原文的章节、段落和内容顺序。\n4. 【硬性要求】专名/术语对照: 小汐→Shio、阿洛→Arlo\n\n只输出译文"))
        assertTrue(p.endsWith("【源文】\n\n小汐和阿洛\n"))
    }

    @Test fun otherDirectionsUseIndexTranslatePrompts() {
        val idx = BuiltInProfiles.INDEX_TRANSLATE
        assertEquals(idx.buildUserPrompt(en, ja, "Hello"), nl.buildUserPrompt(en, ja, "Hello"))
        val terms = listOf(GlossaryEntry("Neko", "猫猫"))
        assertEquals(idx.buildUserPrompt(ko, zh, "Neko", terms), nl.buildUserPrompt(ko, zh, "Neko", terms))
        assertTrue(nl.buildUserPrompt(ko, zh, "Neko", terms).contains("【硬性要求】专名/术语对照: Neko→猫猫"))
    }

    @Test fun autoSourceIsDetected() {
        assertEquals("zh", NativeLongPrompts.detectSource("小汐推开猫咖的门。Cafe"))
        assertEquals("ja", NativeLongPrompts.detectSource("彼女は猫カフェのドアを開けた。"))
        assertEquals("en", NativeLongPrompts.detectSource("She opened the door of the café, said “你好”."))
        assertNull(NativeLongPrompts.detectSource("12345 !!!"))
        assertEquals("ja-zh", NativeLongPrompts.direction(Language.AUTO, zh, "猫カフェ"))
        assertEquals("en-zh", NativeLongPrompts.direction(Language.AUTO, zh, "A long story."))
        assertNull(NativeLongPrompts.direction(Language.AUTO, zh, "中文到中文"))
        assertTrue(nl.buildUserPrompt(Language.AUTO, en, "第一章").startsWith("请将以下中文小说翻译成英语"))
    }

    @Test fun matchesOfficialGgufMetadataAndFileNames() {
        // Real header of Index-Nailong-2B.Q4_K_M.gguf: general.architecture qwen35, general.name "Index Nailong 2B".
        assertSame(nl, BuiltInProfiles.match("qwen35", "Index Nailong 2B", "renamed.gguf"))
        assertSame(nl, BuiltInProfiles.match("qwen35", null, "Index-Nailong-2B.Q4_K_M.gguf"))
        assertSame(nl, BuiltInProfiles.match("qwen35", null, "Index-NativeLong-9B-IQ4_XS.gguf"))
        assertSame(BuiltInProfiles.INDEX_TRANSLATE, BuiltInProfiles.match("qwen35", "2b", "Index-Translate-2B.Q4_K_M.gguf"))
        assertSame(BuiltInProfiles.CUSTOM, BuiltInProfiles.match("llama", null, "Index-Nailong-2B.gguf"))
    }

    @Test fun profileDefaults() {
        assertEquals("index-nativelong", nl.id)
        assertTrue(nl.wholeDocument)
        assertEquals(listOf("长文档 / 整篇翻译"), nl.tags)
        assertEquals(0f, nl.sampling.temperature)
        assertEquals(8192, nl.sampling.maxTokens)
        assertTrue(nl.sampling.maxTokens in ParamScales.MAX_TOKEN_OPTIONS)
        assertTrue(nl.defaultContext!! in ParamScales.contextOptions(262144))
        assertEquals(nl.chatFormat, BuiltInProfiles.INDEX_TRANSLATE.chatFormat)
        assertSame(nl, ModelParams(profileId = "index-nativelong").profile(BuiltInProfiles.CUSTOM))
    }

    @Test fun contextDefaultFollowsProfileUnlessUserSetIt() {
        assertEquals(16384, ModelParams().loadParams(nl).nCtx)
        assertEquals(ModelParams.DEFAULT_CONTEXT, ModelParams().loadParams(BuiltInProfiles.INDEX_TRANSLATE).nCtx)
        assertEquals(32768, ModelParams(nCtx = 32768).loadParams(nl).nCtx)
        assertEquals(ModelParams.DEFAULT_CONTEXT, ModelParams().loadParams(false).nCtx)
    }

    @Test fun tokenEstimateIsConservative() {
        assertEquals(4, TokenEstimate.tokens("小汐推门"))
        assertEquals(3, TokenEstimate.tokens("かなカ"))
        // "Hello world": 10 letters × 0.3 + 1 space × 0.1 = 3.1 -> 4
        assertEquals(4, TokenEstimate.tokens("Hello world"))
        assertEquals(0, TokenEstimate.tokens(""))
        assertEquals(1, TokenEstimate.tokens("😀")) // one code point, not two chars
    }

    @Test fun fitsWholeDocumentArithmetic() {
        // 1000 prompt + 1500 output + 24 + 256 = 2780
        assertTrue(TranslationPlanner.fitsWholeDocument(1000, 1000, 2780, 0))
        assertFalse(TranslationPlanner.fitsWholeDocument(1000, 1000, 2779, 0))
        // the output must also fit the per-generation cap
        assertFalse(TranslationPlanner.fitsWholeDocument(1000, 1000, 16384, 1499))
        assertTrue(TranslationPlanner.fitsWholeDocument(1000, 1000, 16384, 1500))
    }

    @Test fun documentChunkSize() {
        // (16384 - 300 - 24 - 256) / 2.5 = 6321, capped by 8192 / 1.5 = 5461
        assertEquals(5461, TranslationPlanner.documentChunkChars(16384, 8192, 300))
        assertEquals(6321, TranslationPlanner.documentChunkChars(16384, 0, 300))
        assertEquals(TranslationPlanner.DOC_MAX_CHUNK, TranslationPlanner.documentChunkChars(262144, 0, 300))
        assertEquals(TextSegmenter.MIN_LIMIT, TranslationPlanner.documentChunkChars(512, 0, 300))
    }

    private fun chapter(n: Int) = (1..n).joinToString("\n\n") { "第${it}段。小汐推开猫咖的门，雨水顺着伞尖滴落，店主阿洛抬起头来。" }

    @Test fun longTextGoesInOnePieceWhenItFits() {
        val text = chapter(80) // ≈ 2.8K CJK chars
        val plan = TranslationPlanner.plan(nl, zh, en, text, 16384, 8192, emptyList())
        assertEquals(1, plan.size)
        assertEquals(nl.buildUserPrompt(zh, en, text), plan.single().prompt)
        // The same text is split into many ≤800-char pieces for Index-Translate.
        assertTrue(TranslationPlanner.plan(BuiltInProfiles.INDEX_TRANSLATE, zh, en, text, 16384, 8192, emptyList()).size > 3)
    }

    @Test fun wholeDocumentCarriesAllMatchedTermsOnce() {
        val text = chapter(80)
        val glossary = listOf(GlossaryEntry("小汐", "Shio"), GlossaryEntry("阿洛", "Arlo"), GlossaryEntry("不存在", "x"))
        val plan = TranslationPlanner.plan(nl, zh, en, text, 16384, 8192, glossary)
        assertEquals(1, plan.size)
        assertTrue(plan.single().prompt.contains("4. 【硬性要求】专名/术语对照: 小汐→Shio、阿洛→Arlo\n"))
    }

    @Test fun tooLongFallsBackToLargeChunksWithDocumentPrompt() {
        val text = chapter(400) // ≈ 14K CJK chars: more than 16K context can take in one go
        val plan = TranslationPlanner.plan(nl, zh, en, text, 16384, 8192, emptyList())
        assertTrue(plan.size in 2..5)
        plan.forEach { assertTrue(it.prompt.startsWith("请将以下中文小说翻译成英语")) }
        val limit = TranslationPlanner.documentChunkChars(16384, 8192, 0)
        plan.forEach { seg ->
            val body = seg.prompt.substringAfter("【源文】\n\n").removeSuffix("\n")
            assertTrue(body.length <= limit)
        }
        assertEquals("", plan.first().separator)
        assertEquals("\n\n", plan[1].separator)
        // Bigger context: fewer pieces.
        assertTrue(TranslationPlanner.plan(nl, zh, en, text, 32768, 16384, emptyList()).size < plan.size)
    }

    @Test fun lowMaxTokensForcesChunking() {
        val text = chapter(80)
        assertTrue(TranslationPlanner.plan(nl, zh, en, text, 16384, 1024, emptyList()).size > 1)
    }

    @Test fun unloadedContextUsesProfileDefault() {
        val text = chapter(80)
        assertEquals(1, TranslationPlanner.plan(nl, zh, en, text, 0, 8192, emptyList()).size)
    }

    @Test fun officialFilesRecognition() {
        val q4 = OfficialFiles.bySize(1_274_396_384L)!!
        assertEquals("Index-Nailong-2B.Q4_K_M.gguf", q4.fileName)
        assertEquals("Q4_K_M", q4.quant)
        assertEquals(OfficialStatus.VERIFIED, OfficialFiles.match(q4.sizeBytes, q4.sha256.uppercase())!!.status)
        assertEquals(OfficialStatus.SIZE_MATCH, OfficialFiles.match(q4.sizeBytes, null)!!.status)
        assertEquals(OfficialStatus.MISMATCH, OfficialFiles.match(q4.sizeBytes, "0".repeat(64))!!.status)
        assertNull(OfficialFiles.match(1_274_396_383L, q4.sha256))
        // sizes are unique, so a size identifies at most one file
        assertEquals(OfficialFiles.ALL.size, OfficialFiles.ALL.map { it.sizeBytes }.toSet().size)
        assertTrue(OfficialFiles.ALL.all { it.sha256.length == 64 && it.repo == OfficialFiles.NAILONG_2B_REPO })
        assertEquals("00ff10", OfficialFiles.hex(byteArrayOf(0, -1, 16)))
    }
}
