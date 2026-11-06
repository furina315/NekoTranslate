package com.nekohome.translate.data

import kotlin.math.ceil

/** 一段的最终提示词（未套聊天模板）与它和上一段之间的分隔。 */
data class PlannedSegment(val prompt: String, val separator: String)

/**
 * 不跑分词器的 token 粗估（偏保守）。CJK（汉字、假名、谚文）按 1 字 1 token，其余非空白字符
 * 按 0.3 token，空白按 0.1。实测 Index-Nailong-2B（Qwen3.5 分词器）中文约 0.6 token/字，
 * 英文约 0.22 token/字符，所以这里会高估，宁可多分段也不撑爆上下文。
 */
object TokenEstimate {
    fun tokens(text: String): Int {
        var cjk = 0
        var other = 0
        var space = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                Character.isWhitespace(cp) -> space++
                isCjk(cp) -> cjk++
                else -> other++
            }
            i += Character.charCount(cp)
        }
        return cjk + ceil(other * 0.3 + space * 0.1).toInt()
    }

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }
}

object TranslationPlanner {
    /** 预计译文 token = 原文 token × 此系数（与 TextSegmenter 的“最坏 1.5 倍”一致）。 */
    const val OUTPUT_FACTOR = 1.5

    /** 聊天模板（<|im_start|>… / 空 think 块）的固定开销与安全余量。 */
    const val CHAT_OVERHEAD = 24
    const val SAFETY_MARGIN = 256

    /** 整篇翻译时术语表上限：整篇只发一次，比分段时宽一些。 */
    const val DOC_MAX_TERMS = 60
    const val DOC_MAX_TERM_CHARS = 1500

    /** 整篇放不下、按上下文切大块时，每块最多多少字符。 */
    const val DOC_MAX_CHUNK = 12_000

    /**
     * 把 [text] 切成若干段，每段只带本段出现的术语。[glossary] 为空 = 不用术语表。
     * 短文本只有一段，提示词与没有术语表时完全一致。
     *
     * [ModelProfile.wholeDocument] 的模型（Index-NativeLong）先试整篇：估算放得下就单次整篇翻译；
     * 放不下则按上下文切成尽量大的块，每块仍用整篇提示词。
     */
    fun plan(
        profile: ModelProfile,
        source: Language,
        target: Language,
        text: String,
        nCtx: Int,
        maxTokens: Int,
        glossary: List<GlossaryEntry>,
    ): List<PlannedSegment> {
        if (profile.wholeDocument) {
            val ctx = if (nCtx > 0) nCtx else profile.defaultContext ?: ModelParams.DEFAULT_CONTEXT
            return planDocument(profile, source, target, text, ctx, maxTokens, glossary)
        }
        val reserved = if (glossary.isEmpty()) 0 else GlossaryRules.MAX_CHARS_PER_PROMPT + 80
        val limit = TextSegmenter.segmentLimit(nCtx, maxTokens, reserved)
        return planSegments(profile, source, target, text, limit, glossary)
    }

    private fun planSegments(
        profile: ModelProfile,
        source: Language,
        target: Language,
        text: String,
        limit: Int,
        glossary: List<GlossaryEntry>,
    ): List<PlannedSegment> {
        val segments = TextSegmenter.split(text, limit)
        if (segments.isEmpty()) return listOf(PlannedSegment(profile.buildUserPrompt(source, target, text), ""))
        return segments.map { seg ->
            val terms = GlossaryRules.match(seg.text, glossary)
            PlannedSegment(profile.buildUserPrompt(source, target, seg.text, terms), seg.separator)
        }
    }

    private fun planDocument(
        profile: ModelProfile,
        source: Language,
        target: Language,
        text: String,
        nCtx: Int,
        maxTokens: Int,
        glossary: List<GlossaryEntry>,
    ): List<PlannedSegment> {
        val src = text.trim()
        if (src.isEmpty()) return listOf(PlannedSegment(profile.buildUserPrompt(source, target, text), ""))
        val terms = GlossaryRules.match(src, glossary, DOC_MAX_TERMS, DOC_MAX_TERM_CHARS)
        val prompt = profile.buildUserPrompt(source, target, src, terms)
        if (fitsWholeDocument(TokenEstimate.tokens(prompt), TokenEstimate.tokens(src), nCtx, maxTokens)) {
            return listOf(PlannedSegment(prompt, ""))
        }
        // Fixed cost of one chunk's prompt besides its text: the template plus the terms it may carry.
        val overhead = TokenEstimate.tokens(prompt) - TokenEstimate.tokens(src)
        val limit = documentChunkChars(nCtx, maxTokens, overhead)
        return planSegments(profile, source, target, src, limit, glossary)
    }

    /**
     * 整篇能否一次放进上下文：提示词（模板 + 术语 + 原文）+ 预计译文（原文 × [OUTPUT_FACTOR]）
     * + 聊天模板开销 + 余量 ≤ [nCtx]，且预计译文不超过每次生成上限 [maxTokens]（≤0 = 不限）。
     */
    fun fitsWholeDocument(promptTokens: Int, sourceTokens: Int, nCtx: Int, maxTokens: Int): Boolean {
        val output = ceil(sourceTokens * OUTPUT_FACTOR).toInt()
        if (maxTokens in 1 until output) return false
        return promptTokens + output + CHAT_OVERHEAD + SAFETY_MARGIN <= nCtx
    }

    /**
     * 整篇放不下时每块的字符上限，按最坏情况（CJK 1 字 1 token）：块 c 字需要
     * c（原文）+ 1.5c（译文）token，再扣掉提示词固定部分 [promptOverhead]。
     */
    fun documentChunkChars(nCtx: Int, maxTokens: Int, promptOverhead: Int): Int {
        val avail = nCtx - promptOverhead - CHAT_OVERHEAD - SAFETY_MARGIN
        var limit = (avail / (1 + OUTPUT_FACTOR)).toInt()
        if (maxTokens > 0) limit = minOf(limit, (maxTokens / OUTPUT_FACTOR).toInt())
        return limit.coerceIn(TextSegmenter.MIN_LIMIT, DOC_MAX_CHUNK)
    }
}
